# 🔥 Diagnóstico: Request Logger + Kafka - Problemas e Soluções

## 🚨 1) O que acontece quando o Kafka fica indisponível?

Depende de como sua LIB envia os logs (síncrono vs assíncrono) e das configurações do KafkaProducer.

### 🔒 Envio síncrono (producer.send(...).get() / bloqueante):

- A thread que trata a requisição HTTP fica bloqueada até a operação de envio terminar (ou timeout)
- Se o Kafka estiver indisponível ou o buffer do produtor estiver cheio, a chamada pode bloquear por muito tempo (ou até `max.block.ms`), aumentando latência do request
- Threads de servidor (Tomcat/Nio/Undertow/Reactor) podem se esgotar → novas requisições ficam enfileiradas ou rejeitadas

### ⚡ Envio assíncrono, mas sem isolamento (producer.send(callback) direto na thread de request):

- A chamada `send()` normalmente é rápida (enfileira no buffer do produtor), mas se o buffer encher, `send()` pode bloquear até `max.block.ms`
- Mesmo assincronamente, se você fizer lógica de retry/tratamento grande na thread do request, pode bloquear

### ✅ Envio totalmente desacoplado (fila interna + thread(s) produtoras):

- A thread de request só empurra um item para uma fila local (preferencialmente bounded). O envio real é feito por threads dedicadas
- Se o Kafka está down, a fila enche; quando cheia, dependendo da política (bloquear/retornar erro/descartar), terá efeitos diferentes
- **Esse modelo é muito mais resiliente** — evita bloquear threads de request

## 💥 2) Efeitos colaterais práticos (detalhados)

### A. 🧵 Exaustão do pool de threads HTTP

Quando request-threads ficam bloqueadas esperando envio para Kafka, os workers do container (Tomcat/Jetty/Undertow ou threads do Netty/Reactor) são ocupados.

**Consequência**: aumento de latência, timeouts HTTP, erros 503/504, degradação em cascata (clientes re-tentam), e possível queda do serviço.

### B. 🔌 Exaustão de pools de conexão (DB / HTTP / outros recursos)

Threads ocupadas seguram conexões de banco (por exemplo, abrem/retêm transações) por mais tempo; o pool de conexões (HikariCP, etc.) pode esgotar.

**Resultado**: operações DB falham ou enfileiram; mais latência; mais threads esperam por conexão → efeito cascata.

### C. 🧠 Crescimento de memória / GC e OOM

Se você está bufferizando bodies (`ContentCachingRequestWrapper`) e empilhando logs em filas locais, memória pode crescer rapidamente com muitos requests e bodies grandes.

Isso leva a mais GC pausas e risco de OOM.

### D. 📈 Aumento de latência e degradação das SLOs

P95/P99 latências explodem se threads bloquearem; SLAs são afetados.

### E. 🌐 Conexões TCP e recursos do Kafka client

O KafkaProducer possui threads de rede próprias; se Kafka ficar indisponível por muito tempo, producer tentará reconectar, mantendo sockets, timers e buffers. Isso consome CPU/memória.

**⚠️ Importante**: Se criar muitos produtores (ex.: um por request), você amplifica o problema — **nunca crie muitos produtores**.

### F. 📝 Possível perda de logs / inconsistência

Se você optar por descarte durante falha, perde logs; se reter sem limites, arrisca recursos da app.

## ❓ 3) Por que producer.send() pode bloquear?

O produtor mantém um buffer em memória (`buffer.memory`) para mensagens aguardando envio.

Se esse buffer encher (por exemplo, Kafka down), `send()` aguarda espaço livre — bloqueio respeita `max.block.ms`.

Configurações de `acks`, `batch.size`, `linger.ms`, `retries` influenciam latência e memória.

## 🏗️ 4) Regras gerais de design (boas práticas)

### 🔄 Desacoplar o envio de logs da thread de request:
Use uma fila bounded (ex.: `LinkedBlockingQueue` com tamanho limitado) e threads dedicadas de envio.

### 🚫 Nunca bloquear indefinidamente a thread de request:
Ao enfileirar, use `offer(item, timeout)` ou `offer(item)` sem bloqueio; se não conseguir, fallback (escrever em disco, meter métrica, descartar com contador).

### 🛡️ Isolar com Bulkhead / Executor bounded:
Tenha um pool separado e limitado para envio; limite o número de mensagens simultâneas sendo enviadas.

### ⚡ Implementar Circuit Breaker:
Se Kafka falha repetidamente, abrir o circuito e interromper tentativas por um tempo. Use Resilience4j ou Hystrix-like.

### 🌊 Backpressure / políticas de descarte:
Políticas: `DROP_OLDEST`, `DROP_NEW`, ou persistência local.

### 💾 Persistência local como fallback:
Escrever logs em arquivo local (append-only) e um processo de retry off-line que lê o arquivo e tenta reentregar.

### 📊 Métricas e alertas:
Expor métricas: tamanho da fila, taxa de descarte, latência do envio, erros Kafka.

### 📏 Limitar tamanho do body:
Não logue bodies gigantescos — truncar ou amostrar. Configure limite (ex.: 8KB) e um switch `logBodySamplingRate`.

### ♻️ Reusar um KafkaProducer singleton:
Criar um único producer por app (ou pool pequeno), não por request.

### ⚙️ Configurar producer para evitar bloqueios longos:
- `max.block.ms` (como 5000ms) — define tempo máximo que `send()` pode bloquear
- `delivery.timeout.ms`, `request.timeout.ms` controlam timeouts

## 🏛️ 5) Exemplo de arquitetura segura (pseudocódigo + comportamento)

```java
// Componente de logging dentro do filter
class RequestLogSender {
  private final BlockingQueue<LogEvent> queue = new LinkedBlockingQueue<>(5000); // bounded
  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private final KafkaProducer<String, String> producer; // singleton

  public RequestLogSender(KafkaProducer producer) {
    this.producer = producer;
    // iniciar threads consumidoras
    for (int i=0;i<2;i++) {
      workers.submit(this::consumeLoop);
    }
  }

  public boolean submit(LogEvent evt) {
    // tenta enfileirar rapidamente sem bloquear
    boolean accepted = queue.offer(evt);
    if (!accepted) {
      // fila cheia: fallback seguro
      metrics.increment("request_logger.queue_drops");
      // alternativa: gravar em disco append-only para reprocessar
      persistToDisk(evt);
      return false;
    }
    return true;
  }

  private void consumeLoop() {
    while (!Thread.currentThread().isInterrupted()) {
      LogEvent evt = queue.poll(1, TimeUnit.SECONDS);
      if (evt == null) continue;
      try {
        // enviar assíncrono, sem bloquear a lógica de consumo (callback apenas registra)
        ProducerRecord<String,String> rec = new ProducerRecord<>("topic-logs", evt.toJson());
        producer.send(rec, (meta, ex) -> {
           if (ex != null) {
             // registrar e talvez persistir para retry
             metrics.increment("kafka.send.errors");
             persistToDisk(evt);
           } else {
             metrics.increment("kafka.send.success");
           }
        });
      } catch (Exception e) {
        // se producer.send lançar (buffer cheio e max.block.ms estourado) tratar:
        metrics.increment("kafka.send.exception");
        persistToDisk(evt);
      }
    }
  }
}
```

### 🔧 No filter:

```java
// não bloquear: tentar submit e seguir
boolean enqueued = requestLogSender.submit(event);
if (!enqueued) {
  // opcional: adicionar cabeçalho para indicar que a mensagem foi descartada
}
filterChain.doFilter(request, response);
```

## ⚙️ 6) Configurações Kafka importantes e recomendações

| Configuração | Descrição | Recomendação |
|---|---|---|
| `buffer.memory` | Total do buffer do producer | Ex.: 32MB. Se baixo, enche rápido |
| `max.block.ms` | Tempo máximo que send() bloqueia se buffer cheio | **Defina baixo** (ex.: 5000 ms) |
| `delivery.timeout.ms` | Timeout total para entrega (retries inclusive) | Configure adequadamente |
| `request.timeout.ms` | Timeout por request para líder | Balancear com latência |
| `acks` | Confirmação de entrega | `1` reduz latência; `all` é mais seguro |
| `retries` e `retry.backoff.ms` | Configuração para tolerância | Configure para resiliência |
| `linger.ms` e `batch.size` | Para eficiência | Menos requests com batches maiores |

**⚠️ Recomendação**: Não dependa apenas de configurações Kafka para proteger suas aplicações: combine com o design acima (fila bounded + circuit-breaker + fallback).

## 🌊 7) Como isso afeta pools externos (DB, HTTP clients)

- Threads presas seguram conexões DB; transações abertas por mais tempo → Hikari pool exaure → exceções ao tentar obter conexão
- Requisições pendentes também podem manter recursos de sockets abertos
- **Portanto**: qualquer bloqueio na camada HTTP pode causar exaustão de outros pools — **efeito cascata**

## 🔄 8) Estratégias de fallback práticas

### 💾 Persistência em disco (append-only) + job de reentrega
Garante durabilidade local.

### ⚡ Circuit Breaker (abrir circuito)
Parar tentativas quando Kafka instável, reduzir carga.

### 📊 Amostragem (sampling)
Logar só 1% dos requests em picos.

### ✂️ Truncamento de body
Limite N bytes.

### 🚨 Alertas
Alta taxa de descarte/filas cheias → alertar time de infra.

## 📈 9) Observabilidade e testes

### 📊 Expor métricas:
- `queue_size`
- `enqueue_timeouts`
- `drop_rate`
- `kafka_send_errors`
- `producer_buffer_utilization`

### 🧪 Teste em chaos scenarios:
Desligue Kafka e veja comportamento da app (stress test).

### ⏱️ Monitore latências:
P95/P99 dos endpoints.

## 📋 10) Resumo / recomendações concretas (práticas)

### ❌ Nunca faça:
- Enviar logs para Kafka diretamente na thread de request de forma bloqueante

### ✅ Sempre faça:
1. **🔄 Desacople** com uma fila bounded + threads de envio
2. **⚡ Implemente** circuit breaker + fallback local (arquivo) e política de descarte
3. **♻️ Reutilize** um único KafkaProducer (singleton) com configurações sensatas (`max.block.ms` baixo)
4. **📏 Limite** tamanho de body e use amostragem
5. **📊 Instrumente** métricas e alerte antes de chegar a condição crítica
6. **🧪 Teste** o comportamento com Kafka down em ambientes de staging

---

> **💡 Lembre-se**: A resiliência do sistema depende de como você lida com falhas, não de evitá-las completamente!
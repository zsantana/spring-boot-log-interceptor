# Spring Request Logger

Uma biblioteca Spring Boot para logging estruturado de requisições HTTP.

## Características

### ✅ Melhorias Implementadas

1. **Segurança**:
   - Filtra headers sensíveis (Authorization, Cookie, etc.)
   - Limitação do tamanho do body para evitar logs enormes
   - Tratamento seguro de encoding de caracteres

2. **Performance**:
   - Exclusão automática de paths desnecessários (actuator, assets estáticos)
   - Logs condicionais baseados no nível de log configurado
   - Otimização de memory usage

3. **Observabilidade**:
   - Correlation ID único para cada requisição
   - Logs estruturados com informações relevantes
   - Tempo de resposta (duration)
   - IP do cliente (considerando proxies)
   - Logs de request e response

4. **Configurabilidade**:
   - Múltiplas opções de configuração
   - Paths excluídos configuráveis
   - Headers excluídos configuráveis
   - Tamanho máximo do body configurável

5. **Robustez**:
   - Exception handling completo
   - Null safety
   - MDC cleanup automático
   - Fallback para UTF-8 em caso de encoding inválido

## Configuração

```yaml
request-logger:
  enabled: true                    # Habilita/desabilita o logging
  log-headers: true               # Loga headers da requisição
  log-body: false                 # Loga body da requisição
  log-response-body: false        # Loga body da resposta
  max-body-size: 1000            # Tamanho máximo do body em caracteres
  excluded-paths:                 # Paths excluídos do logging
    - "/actuator/**"
    - "/health"
    - "/metrics"
    - "/**/*.css"
    - "/**/*.js"
    - "/**/*.ico"
  excluded-headers:               # Headers adicionais para excluir
    - "x-custom-secret"
```

## Exemplo de Log

```
INFO  - === REQUEST START [a1b2c3d4] ===
INFO  - Method: POST | URI: /api/users | Remote: 192.168.1.100
INFO  - Query: page=1&size=10
INFO  - Header: Content-Type = application/json
INFO  - Header: User-Agent = PostmanRuntime/7.29.0
INFO  - Request Body: {"name":"João","email":"joao@email.com"}
INFO  - === REQUEST END [a1b2c3d4] ===
INFO  - Status: 201 | Duration: 245ms | Content-Type: application/json
INFO  - Response Body: {"id":123,"name":"João","email":"joao@email.com"}
```

## Headers Sensíveis (Automaticamente Excluídos)

- `authorization`
- `cookie`
- `set-cookie`
- `x-auth-token`
- `x-api-key`
- `proxy-authorization`
- `www-authenticate`

## Instalação

### Maven

```xml
<dependency>
    <groupId>com.reinaldo.logger</groupId>
    <artifactId>spring-request-logger</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.reinaldo.logger:spring-request-logger:1.0.0'
```

## Auto-configuração

A biblioteca é automaticamente configurada quando adicionada ao classpath. Não é necessária configuração adicional.

## Boas Práticas

1. **Em Produção**: Configure `log-body: false` para evitar logs sensíveis
2. **Performance**: Use `excluded-paths` para filtrar endpoints desnecessários
3. **Segurança**: Adicione headers sensíveis em `excluded-headers`
4. **Tamanho**: Configure `max-body-size` adequadamente para seu ambiente

## Contribuição

Este projeto segue as melhores práticas de:
- Clean Code
- SOLID Principles
- Spring Boot Conventions
- Security by Design

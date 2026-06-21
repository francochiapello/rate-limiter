# Rate Limiter

Prototipo de un Rate Limiter usando el algoritmo **Token Bucket**: dominio independiente de framework, con un adaptador HTTP delgado sobre Spring Boot para poder probarlo con curl o Postman.

Para el razonamiento completo detrás de cada decisión de diseño (por qué Token Bucket y no Sliding Window, por qué objeto resultado y no excepción, manejo de concurrencia, estrategia de testing, qué se descartó deliberadamente y por qué) ver **[DESIGN.md](./DESIGN.md)**.

## Requisitos

- Java 21
- No es necesario tener Maven instalado: el proyecto usa Maven Wrapper (`./mvnw`).

## Primer uso: generar el wrapper

El repositorio incluye `mvnw` / `mvnw.cmd`, pero no el jar del wrapper (no se distribuye como archivo suelto). Si es la primera vez que cloná este repo, generalo una sola vez con tu Maven local:

```bash
mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.2.0:wrapper -Dmaven=3.9.6
```

Después de este paso, `./mvnw` funciona de forma autónoma — no se vuelve a necesitar Maven instalado.

## Correr la aplicación

```bash
./mvnw spring-boot:run
```

Levanta en `http://localhost:8080`.

## Correr los tests

```bash
./mvnw test
```

Incluye tests unitarios del dominio, tests de concurrencia real (múltiples threads compitiendo por el mismo cliente), y tests del adaptador HTTP con `MockMvc`. Ver la sección 7 de [DESIGN.md](./DESIGN.md) para el detalle de qué se prueba en cada clase y por qué.

## Probar el endpoint

Hay un único endpoint, pensado explícitamente como una demo del rate limiter (no simula un endpoint de negocio):

```
POST /api/rate-limit/check
Header: X-Client-Id: <cualquier string>
```

### Con curl

Request permitida:

```bash
curl -i -X POST http://localhost:8080/api/rate-limit/check \
  -H "X-Client-Id: cliente-1"
```

Respuesta esperada (200, con tokens disponibles):

```
HTTP/1.1 200
X-RateLimit-Remaining: 99

{"allowed":true,"remainingTokens":99,"retryAfterMillis":0}
```

Para ver el rechazo, repetí la misma request muchas veces con el mismo `clientId` (la política default es 100 requests, reponiendo 10 por segundo — ver [DESIGN.md, sección 8.4](./DESIGN.md#84-configuración--deliberadamente-hardcodeada)). Por ejemplo, en bash:

```bash
for i in $(seq 1 105); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/rate-limit/check \
    -H "X-Client-Id: cliente-stress"
done
```

Las primeras 100 deberían devolver `200`, el resto `429`.

Request sin el header obligatorio (error de uso de la API, no de negocio — ver [DESIGN.md, sección 6.1](./DESIGN.md#61-adaptador-http--qué-hace-y-qué-deliberadamente-no-hace)):

```bash
curl -i -X POST http://localhost:8080/api/rate-limit/check
```

Respuesta esperada: `400 Bad Request`.

### Con Postman

Hay una colección lista para importar en [`postman/rate-limiter.postman_collection.json`](./postman/rate-limiter.postman_collection.json), con tres requests:

1. **Allowed request** — caso de éxito (200).
2. **Repeat until rate limited** — pensada para ejecutarse repetidamente (con el botón "Send" varias veces, o corriendo la colección completa con "Run") hasta forzar el `429`.
3. **Missing client id header** — dispara el `400` automático de Spring.

Para importarla: en Postman, `File → Import`, seleccionar el archivo. La variable de colección `baseUrl` ya apunta a `http://localhost:8080`; cambiarla si la aplicación corre en otro puerto.

## Estructura del proyecto

```
src/main/java/com/franchiapello/ratelimiter/
├── RateLimiterApplication.java   # arranque + wiring de beans (único lugar con Spring fuera de adapter/)
├── domain/                        # sin dependencias de Spring
├── infrastructure/                # implementaciones in-memory de los puertos del dominio
└── adapter/http/                  # controller delgado + DTO de respuesta
```

Para el detalle de cada clase y por qué está donde está, ver [DESIGN.md, sección 6](./DESIGN.md#6-estructura-del-proyecto).

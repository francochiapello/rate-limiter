# DESIGN.md — Rate Limiter

> **Estado del documento:** Refleja el proyecto completo: dominio, infraestructura, adaptador HTTP, tests (incluyendo concurrencia), logging, y las decisiones explícitas sobre métricas y configuración. Validado con `./mvnw test` — todos los tests pasan. La sección 9.1 (backend distribuido) documenta un análisis de diseño en profundidad, incluyendo el script Lua evaluado, que se decidió no llevar a código — ver esa sección para la justificación completa.

## Propósito de este documento

Este no es un documento de diseño escrito antes de codear y abandonado después. Es la bitácora de **por qué** el código es como es. Cada decisión acá tiene: la alternativa que se consideró, por qué se descartó, y qué trade-off se aceptó a cambio. El objetivo es que cualquiera que retome este proyecto — humano o IA — entienda el razonamiento sin tener que re-derivarlo.

Contexto del ejercicio: evaluación técnica de System Design Implementation basada en *System Design Interview* (Alex Xu), problema elegido: Rate Limiter. El README de la evaluación prioriza explícitamente: código que compile y pase tests, cobertura sobre la lógica principal, diseño elegante, buen manejo de errores, concurrencia donde corresponda, **y evitar sobreingeniería**. Ese último punto es tan importante como los demás y se referencia varias veces abajo: varias decisiones acá son deliberadamente simples, no por falta de conocimiento de alternativas más sofisticadas, sino porque la alternativa sofisticada no se justifica para este alcance.

---

## 1. Contrato funcional

El primer error que se quiso evitar fue elegir un algoritmo de rate limiting sin antes acotar el problema. "Rate Limiter" no es un problema único; es una familia, y cada miembro de la familia tiene trade-offs distintos. Las siguientes seis preguntas se respondieron **antes** de tocar código.

| # | Pregunta | Decisión | Justificación resumida |
|---|---|---|---|
| 1 | ¿Qué identidad limita el tráfico? | `clientId: String` genérico | El dominio no necesita saber si ese string es una API key, un userId o una IP. Mantiene el dominio desacoplado del transporte (HTTP, gRPC, mensajería). Si en el futuro se necesita limitar por múltiples dimensiones (ej. `clientId + endpoint`), la clave se convierte en un objeto compuesto sin tocar el algoritmo. |
| 2 | ¿Qué garantía necesita el límite? | Tolera bursts controlados | Token Bucket (ver sección 2) |
| 3 | ¿Qué pasa si se excede el límite? | Rechazo inmediato. Sin colas, sin reintentos automáticos, sin espera. HTTP 429 en el adaptador. | Agregar colas o scheduling convierte el problema en "procesamiento diferido", que es un problema distinto y fuera de alcance. |
| 4 | ¿Cuál es la superficie de entrada? | Librería pura (`tryAcquire(clientId)`) + adaptador HTTP delgado encima | El dominio se testea sin levantar infraestructura. El controller HTTP no contiene lógica de negocio, solo traduce. |
| 5 | ¿Una política o múltiples por cliente? | Una única política por cliente, pero **resuelta dinámicamente** vía `RateLimiterConfigProvider` (ver sección 4.1) — distintos clientes pueden tener distintos límites, sin que esto sea "múltiples políticas simultáneas" | Multi-política (ej. 100/min Y 1000/hora simultáneas para el mismo cliente) exige modelar un conjunto de reglas, resolución de conflictos entre reglas, y evaluación combinada. Es la trampa de overengineering más común en este ejercicio. Documentado como evolución futura en la sección 7.2, no implementado. Distinguir esto de "una política por cliente, pero distinta según quién sea el cliente" fue una decisión explícita: lo segundo es trivial de soportar (una interface adicional) y resuelve una pregunta real de entrevista ("¿y si dos clientes necesitan límites distintos?"), por eso sí se implementó. |
| 6 | ¿Cómo se almacena el estado? | En memoria, detrás de una interface (`BucketStore`) | Ver sección 4. |

---

## 2. Algoritmo: Token Bucket — por qué, y por qué no las alternativas

El libro de Xu presenta varios algoritmos. Se evaluaron explícitamente antes de elegir:

**Fixed Window Counter — descartado.**
Permite hasta el doble del límite nominal en el borde entre dos ventanas (una ráfaga al final de una ventana seguida de otra ráfaga al inicio de la siguiente, sin pausa real entre ambas). Es la implementación "ingenua" del problema. Usarla en una evaluación señalaría no haber identificado ese defecto, no una simplificación deliberada.

**Sliding Window Log — descartado.**
Guarda un timestamp por cada request individual de cada cliente. Es preciso, pero el costo de memoria escala con el **volumen de tráfico**, no con la cantidad de clientes — un cliente con tráfico alto consume memoria proporcional a ese tráfico indefinidamente si no hay limpieza activa. Esa limpieza (purgar timestamps viejos) agrega un mecanismo de mantenimiento concurrente que no aporta nada al objetivo del ejercicio: sería complejidad para impresionar, no complejidad necesaria.

**Sliding Window Counter — descartado.**
Es la mejor aproximación matemática (interpola entre ventana actual y anterior), pero es notablemente más difícil de explicar y de testear que Token Bucket sin ganar nada relevante para este alcance. Sería la elección correcta si la prioridad fuera "máxima precisión en un sistema distribuido de producción" — no es el caso de este prototipo.

**Token Bucket — elegido.**
- Estado mínimo por cliente: dos campos (`availableTokens`, `lastRefillTimestampMillis`). No una lista, no un mapa de timestamps.
- Refill **lazy**: se calcula en el momento de la consulta (`tryConsume`), no con un proceso de fondo. Esto significa **cero threads adicionales** y cero necesidad de un componente de limpieza activo (ej. `ScheduledExecutorService`), que habría sido complejidad añadida sin necesidad real.
- Permite bursts hasta la capacidad configurada — comportamiento deseable y fácil de explicar con una analogía simple (un balde que gotea tokens y se vacía con cada request).
- La superficie de concurrencia se reduce a proteger la lectura-modificación de **dos números**. Eso hace que el problema de "thread-safety" sea acotado, testeable, y genuinamente discutible en una entrevista, sin ser rebuscado.

---

## 3. Modelado del resultado: objeto valor, no excepción

`tryAcquire` / `tryConsume` devuelven un `RateLimitResult` (record inmutable: `allowed`, `remainingTokens`, `retryAfterMillis`), no lanzan una excepción cuando el límite se excede.

**Razón de fondo (no de estilo):** una excepción en Java debería representar una condición de la que el código que llama no espera tener que recuperarse en cada invocación normal. Un rechazo de rate limit no es eso — es uno de los dos resultados esperados de la operación, igual que `Optional.empty()` no es excepcional cuando se busca algo que puede no existir. Modelarlo como excepción fuerza `try/catch` para una rama de negocio normal y hace los tests más ruidosos (`assertThrows` esconde más información que comparar campos de un objeto).

**Razón práctica:** se necesita devolver `remainingTokens` también en el camino exitoso (para headers tipo `X-RateLimit-Remaining` en una respuesta 200, no solo en el 429). Con excepción, se termina con dos mecanismos distintos para transportar el mismo tipo de dato — uno para éxito (return value) y otro para rechazo (excepción) — cuando un solo objeto resultado cubre ambos casos.

**Dónde sí se usan excepciones en este proyecto:** errores de configuración inválida (`RateLimiterConfig` con `capacity <= 0`, por ejemplo) y fallas de infraestructura. Esos sí son condiciones verdaderamente excepcionales, no ramas esperadas del dominio del rate limiter.

```java
public record RateLimitResult(boolean allowed, long remainingTokens, long retryAfterMillis) {
    public static RateLimitResult allow(long remainingTokens) { ... }
    public static RateLimitResult deny(long retryAfterMillis) { ... }
}
```

Dos factory methods en vez de exponer el constructor directamente, para que ningún caller pueda construir un estado inconsistente (ej. `allowed=true` con `retryAfterMillis > 0`, que no tiene sentido de negocio).

---

## 4. Almacenamiento de estado: en memoria, detrás de una interface

**Decisión:** el estado vive en memoria (`ConcurrentHashMap`), pero `TokenBucketRateLimiter` no depende de esa estructura directamente — depende de una interface, `BucketStore`.

```java
public interface BucketStore {
    Bucket getOrCreate(String clientId, RateLimiterConfig config);
}
```

### Por qué la interface existe — y por qué la justificación correcta importa

La justificación **incorrecta**, la que no se sostiene bien si te preguntan en una entrevista, sería "por si el día de mañana cambiamos a Redis". Esa frase sola no justifica nada si no hay una razón concreta *hoy* para la interface — sería especulación, exactamente el tipo de abstracción prematura que el README pide evitar.

La justificación **correcta**, la que sí se sostiene: **testabilidad**. Sin la interface, testear `TokenBucketRateLimiter` de forma aislada requiere usar la implementación real (`ConcurrentHashMap`), lo cual no es grave pero impide, por ejemplo, inyectar un store falso que permita inspeccionar o forzar el estado de un `Bucket` directamente (ej. forzar `lastRefillTimestampMillis` a un valor pasado para testear el refill sin `Thread.sleep` ni mockear el reloj del sistema). Esa necesidad de test es la que justifica la interface — no la posibilidad de Redis.

**Costo aceptado, a propósito:** esta interface tiene un solo método y una sola implementación real (`InMemoryBucketStore`) en todo el proyecto. Eso es, técnicamente, una violación leve del principio "esperar a la segunda implementación antes de abstraer". Se acepta ese costo porque el beneficio de testabilidad es concreto y se usa, no hipotético.

**Nota de ubicación:** `BucketStore` vive en el paquete `domain`, no en `infrastructure`. En Ports & Adapters, los puertos (lo que el dominio *necesita*) viven en el dominio; las implementaciones concretas (los adaptadores, ej. `InMemoryBucketStore`) viven en infraestructura. Si la interface viviera en `infrastructure`, el paquete `domain` tendría que importar `infrastructure` para usarla, invirtiendo la dirección de dependencia que esta separación está pensada para proteger.

### Por qué `getOrCreate` es un único método atómico, y no `get` + `save`

Si el contrato expusiera `get(clientId)` y `save(clientId, bucket)` como operaciones separadas, la responsabilidad de orquestar "si no existe, crear; si existe, traer" recaería en el caller (`TokenBucketRateLimiter`) — y ese es precisamente el tipo de check-then-act que genera una carrera bajo concurrencia: dos threads podrían evaluar "no existe" al mismo tiempo y crear dos buckets distintos para el mismo `clientId` en su primera invocación. Al exponer `getOrCreate` como una sola operación, la atomicidad de "buscar o crear" queda garantizada por el store (apoyado en `ConcurrentHashMap.computeIfAbsent`), no por disciplina del caller.

```java
public class InMemoryBucketStore implements BucketStore {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Bucket getOrCreate(String clientId, RateLimiterConfig config) {
        return buckets.computeIfAbsent(clientId, id -> Bucket.newFull(config));
    }
}
```

Que la implementación sea una sola línea de lógica real no es señal de que la interface esté de más — es señal de que la complejidad de concurrencia está correctamente delegada a una estructura ya probada (`ConcurrentHashMap`), en vez de reinventada a mano.

### Qué se descarta explícitamente acá: métodos especulativos

No se agregan `remove`, `clear`, `size`, ni nada de administración del store. Ningún caso de uso actual los pide. Si más adelante se necesita un endpoint de administración o limpieza de clientes inactivos, se agregan en ese momento — agregarlos antes sería abstracción especulativa.

## 4.1 `RateLimiterConfigProvider` — una política por cliente, pero no la misma para todos

Inicialmente el contrato funcional (sección 1, decisión 5) consideraba una única política global para todo el sistema. Se amplió a una política **resuelta por cliente** mediante un segundo puerto:

```java
public interface RateLimiterConfigProvider {
    RateLimiterConfig resolve(String clientId);
}
```

**Por qué esto no es "múltiples políticas" (lo que se descartó en la sección 1):** multi-política significa varias reglas simultáneas aplicadas al *mismo* cliente (ej. 100/min Y 1000/hora a la vez, evaluadas juntas). Esto es distinto: cada cliente sigue teniendo una única regla, solo que la regla puede ser distinta según quién sea el cliente. El costo de implementación es una interface chica; el costo de multi-política real sería modelar conjuntos de reglas y su evaluación combinada. Son problemas de complejidad muy distinta aunque suenen parecidos en una primera lectura.

**Decisión sobre clientes no registrados:** `resolve` nunca lanza excepción ni devuelve `null` — siempre devuelve una `RateLimiterConfig` válida. Si el cliente no tiene una configuración explícita, la implementación devuelve una configuración default. Esta decisión se tomó deliberadamente en lugar de la alternativa (lanzar excepción, o devolver `Optional<RateLimiterConfig>` y forzar al caller a resolver el default): en un rate limiter real, que un cliente nuevo caiga en una política default razonable es el comportamiento esperado y seguro, no una condición de error. Forzar una excepción ahí introduciría una rama de manejo de error ("¿bloqueo todo tráfico no registrado?") que el contrato funcional no pide.

**Dónde vive el default:** en la implementación concreta (`InMemoryRateLimiterConfigProvider`), no en el dominio. Qué política aplicar a un cliente desconocido es una decisión de configuración del sistema desplegado, no una regla del algoritmo de rate limiting en sí.

```java
public class InMemoryRateLimiterConfigProvider implements RateLimiterConfigProvider {
    private final Map<String, RateLimiterConfig> configsByClientId;
    private final RateLimiterConfig defaultConfig;
    // ...
    @Override
    public RateLimiterConfig resolve(String clientId) {
        return configsByClientId.getOrDefault(clientId, defaultConfig);
    }
}
```

---


## 5. Concurrencia: lock por bucket, no lock-free

**Decisión:** cada `Bucket` protege su propio estado con un lock privado (`synchronized` sobre un `Object` interno), no con una estrategia lock-free basada en CAS (`compareAndSet` en loop).

**Alternativa considerada:** diseño lock-free con `AtomicLong`/CAS. Evita que un thread se bloquee esperando a otro, pero el código de refill + consumo se vuelve más difícil de razonar correctamente — hay que reintentar la operación si otro thread modificó el valor en el medio, y un bug en esa lógica es mucho más sutil de detectar con tests que un bug en una sección protegida por lock.

**Por qué se elige el lock:** la diferencia de performance entre ambos enfoques es irrelevante a la escala de este prototipo — no hay un hot path de millones de requests/segundo donde la contención de un lock por bucket importe. Dado que el README prioriza explícitamente "código simple y legible", lock por bucket es la opción que se sostiene mejor: es más fácil de leer, de razonar, y de testear, sin sacrificar corrección.

**Detalle de implementación que importa:** el lock es un `Object` privado dentro de `Bucket`, no `synchronized` en la firma del método (que sincronizaría sobre `this`). Sincronizar sobre `this` expondría el mecanismo de lock a cualquier código externo con una referencia al `Bucket` — alguien podría hacer `synchronized(bucket) { ... }` desde afuera y bloquear el bucket sin que la clase lo sepa o lo controle. Usar un lock privado interno es encapsular el mecanismo de concurrencia, no solo el estado.

```java
public final class Bucket {
    private final Object lock = new Object();
    private long availableTokens;
    private long lastRefillTimestampMillis;

    public RateLimitResult tryConsume(RateLimiterConfig config) {
        synchronized (lock) {
            refill(config);
            if (availableTokens > 0) {
                availableTokens--;
                return RateLimitResult.allow(availableTokens);
            }
            return RateLimitResult.deny(millisUntilNextToken(config));
        }
    }
    // refill() y millisUntilNextToken() — ver código fuente
}
```

### Detalle no trivial: el timestamp de refill avanza en múltiplos exactos del período, no se resetea a "ahora"

Esto es lo que distingue un Token Bucket correcto de uno con *drift*. Si `lastRefillTimestampMillis` se reseteara a `System.currentTimeMillis()` en cada refill, las fracciones de período que no llegaron a completar un token se perderían en cada cálculo, y el bucket se recargaría más lento de lo que la configuración indica. Avanzando el timestamp en múltiplos exactos del período (`periodsElapsed * periodMillis`), el tiempo sobrante dentro del período actual se conserva para el cálculo siguiente.

### Separación deliberada: el `Bucket` no conoce su propia política

`tryConsume(RateLimiterConfig config)` recibe la configuración como parámetro; el `Bucket` no la guarda como campo propio. El `Bucket` es puro estado (cuántos tokens hay, desde cuándo); la política (cuánto, cada cuánto) vive en `TokenBucketRateLimiter`. Esto no se hizo pensando en un requisito futuro específico — es una consecuencia de separar estado de política, que da como efecto colateral la posibilidad de cambiar la configuración de un cliente sin recrear su bucket, sin que eso haya sido el objetivo original.

---

## 6. Estructura del proyecto

```
ratelimiter/
├── RateLimiterApplication.java            # @SpringBootApplication + wiring de beans
├── domain/                                 # CERO dependencias de Spring
│   ├── RateLimiter.java                     # puerto principal
│   ├── TokenBucketRateLimiter.java          # implementacion + logging de rechazos
│   ├── RateLimiterConfig.java               # value object (record)
│   ├── RateLimiterConfigProvider.java       # puerto — politica por cliente
│   ├── RateLimitResult.java                 # value object (record)
│   ├── Bucket.java                          # estado + lock + Clock inyectable
│   └── BucketStore.java                     # puerto — almacenamiento
├── infrastructure/                          # implementaciones concretas, sin Spring
│   ├── InMemoryBucketStore.java
│   └── InMemoryRateLimiterConfigProvider.java
└── adapter/
    └── http/                                 # unico lugar donde Spring entra
        ├── RateLimitController.java
        └── RateLimitCheckResponse.java        # DTO, separado de RateLimitResult
```

**Por qué el dominio no tiene ninguna dependencia de Spring:** todo lo que está bajo `domain/` se puede instanciar y testear con `new`, sin contexto de aplicación, sin mocks de framework. La única excepción aceptada es SLF4J (ver sección 8.2), que es una fachada de logging, no un framework de aplicación. El único lugar donde Spring Boot entra de verdad es `adapter/http/` y el wiring centralizado en `RateLimiterApplication`.

**Por qué el wiring de beans está centralizado en `RateLimiterApplication` (vía `@Bean` methods) en vez de anotar las clases del dominio con `@Component`/`@Service`:** anotar `TokenBucketRateLimiter` o `InMemoryBucketStore` directamente obligaría a importar anotaciones de Spring dentro de `domain/` e `infrastructure/`, rompiendo la garantía de independencia de framework. Centralizar el wiring cuesta unas pocas líneas más en un solo archivo, a cambio de mantener el resto del código completamente libre de Spring.

## 6.1 Adaptador HTTP — qué hace y qué deliberadamente no hace

El controller (`RateLimitController`) tiene exactamente tres responsabilidades: extraer el `clientId`, delegar en `RateLimiter.tryAcquire`, y traducir el `RateLimitResult` a status code + headers + body. Ninguna decisión de negocio vive ahí.

**Endpoint:** `POST /api/rate-limit/check`. Se eligió un endpoint explícitamente sobre el rate limiter mismo, en vez de simular un endpoint de negocio (ej. un `/ping` inventado) protegido por el limiter — fingir un dominio de negocio falso solo para tener algo que envolver habría sido ruido.

**Identidad del cliente:** header `X-Client-Id`, no path param ni query param. Es metadata sobre quién hace la llamada, no parte del recurso solicitado ni un filtro opcional.

**Respuesta:**
- Status `200 OK` si se permite, `429 Too Many Requests` si se rechaza.
- Header `X-RateLimit-Remaining` siempre presente.
- Header `Retry-After` solo en rechazos, **convertido a segundos** (no milisegundos): es la unidad que exige el estándar HTTP (RFC 9110) para ese header, con un mínimo de 1 segundo para no enviar `Retry-After: 0` cuando faltan, por ejemplo, 200ms.
- Body JSON (`RateLimitCheckResponse`) con `allowed`, `remainingTokens`, `retryAfterMillis` (estos sí en milisegundos crudos, porque ahí no hay convención HTTP que respetar).

**Por qué existe `RateLimitCheckResponse` como DTO separado de `RateLimitResult`:** si el controller devolviera directamente el `RateLimitResult` del dominio, el dominio terminaría acoplado a la serialización JSON de Spring. El DTO mantiene `RateLimitResult` como un value object puro que no sabe que existe HTTP.

**Header ausente:** no se maneja con un `if` explícito. `@RequestHeader("X-Client-Id") String clientId` sin `required = false` hace que Spring devuelva automáticamente `400 Bad Request` antes de que el controller se ejecute. No se agregó `@ControllerAdvice` para personalizar ese mensaje: sería pulir un caso de uso incorrecto de la API en un prototipo cuyo foco es el dominio.

---

## 7. Estrategia de testing

| Clase | Qué se prueba | Técnica | Por qué esa técnica |
|---|---|---|---|
| `RateLimiterConfig` | Validación del constructor, incluyendo el caso límite válido `refillTokens > capacity` | Unitario puro, `@ParameterizedTest` para casos inválidos repetitivos | No hay nada externo que controlar |
| `Bucket` (consumo) | Consumo normal, agotamiento exacto en `capacity`, consistencia de `allow`/`deny` | Unitario puro, `FixedClock.at(0)` sin avanzar el tiempo | Aísla consumo de refill para no mezclar dos preocupaciones en la misma clase de test |
| `Bucket` (refill) | Refill exacto tras un período, no-refill antes de completarlo, tope en `capacity`, **ausencia de drift** | Unitario puro, `FixedClock` avanzado manualmente con `advanceMillis` | Sin un reloj inyectable, esto solo se podría probar con `Thread.sleep` real: lento, flaky, e impreciso en los bordes de un período (ver sección 5) |
| `Bucket` (concurrencia) | N threads compitiendo por el **mismo** bucket nunca exceden `capacity`, ni de más ni de menos | `ExecutorService` + doble `CountDownLatch` (sincroniza arranque, libera a todos a la vez) + `AtomicInteger` | Lanzar tareas sin sincronizar su arranque reduce la contención real y puede dejar pasar un bug de concurrencia. La aserción es `assertEquals(capacity, allowedCount)`, no `<=`: un lock roto también puede manifestarse como menos permisos de los que corresponden (un incremento perdido), no solo más |
| `InMemoryBucketStore` | Misma instancia en llamadas repetidas; clientes distintos con buckets independientes; **bajo concurrencia, el primer acceso de N threads al mismo `clientId` crea exactamente un bucket** | Unitario + concurrencia (mismo patrón de doble latch) | Sin esta prueba, una implementación con `get`+`save` separados (en vez de `computeIfAbsent`) podría pasar tests secuenciales y fallar solo bajo concurrencia real |
| `InMemoryRateLimiterConfigProvider` | Cliente registrado devuelve su config explícita; desconocido cae en el default | Unitario puro | No hay race condition posible en una lectura de `getOrDefault` |
| `TokenBucketRateLimiter` | Orquestación: resuelve config, pide el bucket correcto, propaga el resultado sin alterarlo; valida `clientId` inválido y colaboradores nulos en el constructor | Mocks de **interfaces** (`BucketStore`, `RateLimiterConfigProvider`); `Bucket` se usa como **instancia real**, no mockeada | `Bucket` es una clase `final` que representa estado, no un puerto — convertirla en interface solo para mockearla sería abstracción innecesaria, y Mockito requiere configuración adicional para mockear clases `final`. Un `Bucket` real además verifica la orquestación de punta a punta |
| `RateLimitController` | Mapeo a status/headers/body en ambos casos, caso límite de `Retry-After` con mínimo 1 segundo, header ausente → 400 | `@WebMvcTest` + `MockMvc`, mockeando `RateLimiter` con `@MockBean` | Levanta solo la capa web, sin dominio real ni servidor completo; el objetivo es el mapeo HTTP |

**Una corrección propia que vale la pena dejar registrada:** un primer borrador del test de drift incluía una aserción de la forma `resultado.allowed() || resultado.remainingTokens() >= 0` — siempre verdadera, porque `remainingTokens` es un `long` que nunca es negativo en este diseño, y por lo tanto incapaz de fallar nunca. Se corrigió antes de integrarla: la versión final calcula matemáticamente cuántos tokens deberían existir en el punto exacto que se está probando y afirma sobre eso. Una aserción que no puede fallar no es cobertura, es ruido con forma de test.

---

## 8. Manejo de errores, logging, métricas y configuración

### 8.1 Manejo de errores — el criterio aplicado consistentemente

- **Resultado esperado de negocio → objeto resultado** (`RateLimitResult`). Un rechazo no es una falla, es uno de los dos desenlaces normales (ver sección 3).
- **Uso incorrecto de la API o configuración inválida → excepción.** `RateLimiterConfig` lanza `IllegalArgumentException` en su constructor si `capacity`/`refillTokens`/`refillPeriod` son inválidos; `TokenBucketRateLimiter.tryAcquire` lanza `IllegalArgumentException` si `clientId` es `null`/vacío; su constructor lanza `NullPointerException` (vía `Objects.requireNonNull`) si algún colaborador es `null`. Ninguno es una rama de negocio esperada.
- **En el adaptador HTTP:** un header faltante se traduce automáticamente a `400 Bad Request` por Spring, sin código adicional — mismo criterio, resuelto en la capa que corresponde.

### 8.2 Logging

Se agregó un log a nivel `INFO` en `TokenBucketRateLimiter.tryAcquire`, únicamente en rechazos, vía SLF4J:

```java
if (!result.allowed()) {
    log.info("Rate limit exceeded for clientId={}, retryAfterMillis={}",
            clientId, result.retryAfterMillis());
}
```

**Por qué SLF4J no rompe la independencia de framework del dominio:** es una fachada de logging (interface pura), no una implementación ni un framework de aplicación. Qué pasa con ese log (formato, destino, nivel) lo decide la configuración de infraestructura, no el dominio — el mismo principio de inversión de dependencias aplicado con `BucketStore` y `RateLimiterConfigProvider`.

**Por qué vive en `TokenBucketRateLimiter` y no en `Bucket`:** `Bucket` es la unidad de estado más fina e invocada con mayor frecuencia; atarle logging complicaría su testeo en aislamiento y acoplaría una decisión operacional al mecanismo interno. `TokenBucketRateLimiter` conoce el `clientId` (que `Bucket` ni recibe) y es el punto de orquestación — el lugar natural para una señal operacional.

**Por qué solo se loggean rechazos, no cada request permitida:** loggear cada éxito generaría volumen proporcional a todo el tráfico sin aportar más información que "el sistema funciona" — ruido, no señal.

**Por qué no hay un test que verifique la emisión del log:** requeriría capturar el output de SLF4J (ej. `ListAppender` de Logback) para verificar una línea informativa que no es una decisión de negocio. El costo no se justifica frente a lo que aporta.

### 8.3 Métricas — deliberadamente no implementadas

Se evaluó agregar métricas (contador de permitidos/rechazados vía Micrometer + Actuator) y se decidió documentarlo en vez de implementarlo.

**Por qué no:** agrega una dependencia más y una superficie nueva (endpoints de métricas, configuración de exportación) a un prototipo cuyo foco es el dominio. A diferencia del logging (una línea, sin dependencias nuevas, valor inmediato), métricas reales exigen decidir qué se mide y con qué cardinalidad de labels (¿por `clientId`? podría explotar la cardinalidad con muchos clientes) — decisiones de diseño propias que no aportan a demostrar el algoritmo en sí.

**Cómo evolucionaría:** un `MeterRegistry` de Micrometer inyectado en un decorador que envuelva `RateLimiter` sin tocar su lógica (patrón Decorator), incrementando contadores `rate_limiter.requests.allowed`/`denied` con tag `clientId`, expuestos vía Actuator.

### 8.4 Configuración — deliberadamente hardcodeada

La política default (`capacity=100, refillTokens=10, refillPeriod=1s`) está hardcodeada en `RateLimiterApplication`, no externalizada a `application.yml`.

**Por qué no:** externalizarla sería más "production-ready" en apariencia, pero es trabajo de plumbing (parseo de `Duration` desde YAML, `@ConfigurationProperties`) sin relación con el algoritmo de rate limiting, que es lo que esta evaluación pide demostrar.

**Cómo evolucionaría:** un `@ConfigurationProperties("rate-limiter")` con los defaults, y un mecanismo de carga de configuraciones por cliente desde archivo o variable de entorno, inyectado en el `@Bean` de `RateLimiterConfigProvider`. El punto de extensión ya existe (`RateLimiterConfigProvider` no sabe de dónde viene la configuración); cambiar la fuente es trabajo de infraestructura, no de diseño.

---

## 9. Evoluciones futuras documentadas, no implementadas

Estas son extensiones reales y conocidas del problema. Se documentan acá explícitamente para señalar que se conocen y se evaluaron — y se explica por qué no forman parte de este prototipo.

### 9.1 Backend distribuido (Redis)

**Problema que resolvería:** esta implementación es correcta solo en un proceso único. Si la aplicación corre en múltiples instancias detrás de un load balancer, cada instancia tiene su propio `ConcurrentHashMap` — un mismo cliente podría consumir su cuota completa contra cada instancia por separado, multiplicando el límite real por la cantidad de instancias.

**Contexto de esta sección:** durante el proceso de evaluación se consultó explícitamente el alcance esperado, y la respuesta confirmó que se espera que *"a nivel de arquitectura se pueda extender a múltiples instancias y que tenga el almacenamiento para que pueda adaptarse a más tráfico"*. Esto llevó a diseñar en detalle — no solo a mencionar — la implementación con Redis, incluyendo el script Lua completo, antes de decidir si correspondía implementarla en código. El razonamiento completo de esa evaluación queda documentado abajo, junto con la decisión final y su justificación.

**Por qué Redis y no otra alternativa de almacenamiento distribuido:** Redis es la opción estándar de la industria para este problema específico (rate limiting distribuido) por tres razones concretas: latencia de sub-milisegundo (crítico porque el rate limiter está en el camino crítico de cada request), soporte nativo de scripts atómicos del lado del servidor (necesario para preservar la garantía de atomicidad que `ConcurrentHashMap.computeIfAbsent` da gratis en memoria), y estructuras de datos con expiración nativa (`PEXPIRE`), que evitan tener que implementar a mano la limpieza de clientes inactivos.

**Cómo se diseñó la migración — el script Lua evaluado:**

La atomicidad de "leer estado, calcular refill, decrementar, guardar" se preserva con un script Lua ejecutado vía `EVAL`. Redis garantiza que un script Lua se ejecuta de forma atómica respecto de cualquier otro cliente conectado — es el equivalente, en Redis, al lock por bucket que protege a `Bucket.tryConsume` en memoria.

```lua
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillTokens = tonumber(ARGV[2])
local refillPeriodMillis = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'lastRefill')
local tokens = tonumber(bucket[1])
local lastRefill = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    lastRefill = now
end

local elapsed = now - lastRefill
if elapsed > 0 then
    local periodsElapsed = math.floor(elapsed / refillPeriodMillis)
    if periodsElapsed > 0 then
        tokens = math.min(capacity, tokens + periodsElapsed * refillTokens)
        lastRefill = lastRefill + periodsElapsed * refillPeriodMillis
    end
end

local allowed = 0
local retryAfter = 0
if tokens > 0 then
    tokens = tokens - 1
    allowed = 1
else
    local elapsedInCurrentPeriod = (now - lastRefill) % refillPeriodMillis
    retryAfter = refillPeriodMillis - elapsedInCurrentPeriod
end

redis.call('HMSET', key, 'tokens', tokens, 'lastRefill', lastRefill)
redis.call('PEXPIRE', key, refillPeriodMillis * capacity * 2)

return {allowed, tokens, retryAfter}
```

Este script replica exactamente el mismo algoritmo de `Bucket.refill()` y `Bucket.tryConsume()`: refill lazy (se calcula en el momento de la consulta, sin proceso de fondo), avance del timestamp en múltiplos exactos del período (sin drift — la misma técnica que evita el drift en la versión Java), y tope en `capacity` vía `math.min`. El reloj (`now`) se sigue calculando en el lado de la aplicación Java (con el mismo `Clock` ya inyectado en `Bucket`) y se pasa como parámetro al script — Lua no decide la hora, solo opera sobre el valor que recibe, preservando la misma separación entre "quién sabe la hora" y "quién calcula el refill" que ya existe en el diseño actual.

**Alternativa considerada y descartada — `WATCH`/`MULTI`/`EXEC` (optimistic locking) sin Lua:** Redis permite lograr una atomicidad equivalente sin scripts, usando `WATCH` sobre la clave, leyendo el estado, calculando en el cliente, y confirmando con `MULTI`/`EXEC` (que falla si la clave cambió entre el `WATCH` y el `EXEC`, forzando un reintento). Se descartó frente a Lua porque introduce un bucle de reintento explícito en el código Java (con el caso límite de qué hacer si los reintentos se agotan bajo alta contención), mientras que Lua resuelve la atomicidad del lado del servidor sin esa complejidad adicional en el cliente.

**Decisión final: no se implementó en código.** La razón no es de complejidad de líneas de código — el script Lua y su `RedisBucketStore` correspondiente son piezas acotadas, del mismo orden de magnitud que el resto de las clases de este proyecto. La razón es otra, y se documenta con la misma honestidad que el resto de este archivo: implementar Lua sin poder defenderlo con la misma solidez línea por línea que el resto del código de este proyecto sería peor evidencia que no implementarlo y documentar el análisis completo. El criterio aplicado en todo este proyecto fue no incluir nada que no se pueda justificar con seguridad si se cuestiona en una entrevista — y ese mismo criterio, aplicado con honestidad, es el que decide no incluir el script Lua como código ejecutable en esta entrega.

Lo que sí se sostiene, y es lo que se quiere transmitir con esta sección: el punto de extensión (`BucketStore`) fue diseñado desde el principio para que esta migración sea posible sin tocar el algoritmo ni el resto del dominio, y el trabajo de diseño de la migración se hizo en detalle real (no solo en una frase) para confirmar que esa promesa arquitectónica se sostiene en la práctica, no solo en la teoría.

### 9.2 Múltiples políticas simultáneas por cliente

**Problema que resolvería:** límites combinados, como "100 requests/minuto Y 1000 requests/hora" simultáneamente (patrón usado por APIs como Stripe), donde ambas reglas deben cumplirse a la vez.

**Cómo evolucionaría:** `TokenBucketRateLimiter` pasaría de mantener un `Bucket` por cliente a mantener una colección de `Bucket`s por cliente (uno por regla), y `tryAcquire` exigiría que **todas** las reglas permitan la request, no alcanza con que una lo haga.

**Por qué no se implementa ahora:** requiere modelar un conjunto de reglas, definir su evaluación combinada y resolver qué pasa si una regla permite y otra no (¿se consume el token de la que sí permitió, si la otra rechazó?). Es complejidad real, no decorativa, y no está pedida por el alcance del ejercicio.

### 9.3 Rate limiting por múltiples dimensiones (ej. `clientId + endpoint`)

**Problema que resolvería:** limitar no solo por cliente, sino por la combinación cliente+recurso (ej. un cliente puede tener límites distintos para `/search` y para `/checkout`).

**Cómo evolucionaría:** la clave de `BucketStore.getOrCreate` pasaría de `String clientId` a un objeto de clave compuesta (ej. `RateLimitKey(clientId, resource)`), sin necesidad de modificar el algoritmo de Token Bucket en sí — el cambio queda contenido en cómo se construye la clave antes de llegar al store.

**Por qué no se implementa ahora:** no hay un caso de uso concreto en el alcance actual que lo requiera; el dominio ya está diseñado de forma que este cambio sería de bajo impacto si se necesitara.

### 9.4 Métricas y configuración externa

Ver secciones 8.3 y 8.4 respectivamente — documentadas en detalle ahí, no se repiten aquí.

---

## 10. Estado final

Todo lo planteado en este documento está implementado y validado con `./mvnw test` (todos los tests pasan):

- Dominio completo (`RateLimiter`, `TokenBucketRateLimiter`, `Bucket`, `RateLimiterConfig`, `RateLimitResult`, `BucketStore`, `RateLimiterConfigProvider`), sin ninguna dependencia de Spring.
- Infraestructura in-memory (`InMemoryBucketStore`, `InMemoryRateLimiterConfigProvider`).
- Adaptador HTTP delgado (`RateLimitController`, `RateLimitCheckResponse`).
- Logging mínimo en el punto de rechazo.
- Suite de tests cubriendo: validación de configuración, comportamiento de consumo, comportamiento de refill (incluyendo ausencia de drift), concurrencia real en `Bucket` y en `InMemoryBucketStore`, orquestación de `TokenBucketRateLimiter`, y mapeo HTTP del controller.

Las únicas piezas deliberadamente no implementadas (métricas, configuración externa, backend distribuido, múltiples políticas simultáneas, rate limiting multi-dimensional) están documentadas en la sección 9, con la razón concreta de por qué no forman parte de este prototipo y cómo evolucionarían si fuera necesario.

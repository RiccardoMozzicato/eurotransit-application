# Struttura di un servizio Spring Boot

Un servizio Spring Boot è organizzato a strati attraversati dall'alto verso il basso da ogni richiesta. Un'analogia utile: pensa a un ristorante.

```text
   HTTP in                    Kafka in
      │                          │
 ┌────▼─────────┐         ┌──────▼──────────┐
 │ Controller   │         │ Listener        │   ← STRATO BORDO (parla col mondo)
 │ (cameriere)  │         │ (chi ascolta)   │
 └────┬─────────┘         └──────┬──────────┘
      └───────────┬──────────────┘
            ┌──────▼──────┐
            │  Service    │                      ← STRATO LOGICA (le decisioni, @Transactional)
            │  (cucina)   │
            └──────┬──────┘
            ┌──────▼──────┐
            │ Repository  │                      ← STRATO DATI (legge/scrive il DB)
            │ (dispensa)  │
            └──────┬──────┘
            ┌──────▼──────┐
            │  Entity     │                      ← I DATI (la riga di tabella)
            └─────────────┘
```

Accanto a questi strati ci sono: `Application.kt` (l'interruttore d'accensione) e i file di configurazione (`@Configuration`, eventi, gateway) che cablano il sistema.

## Dizionario dei suffissi

Una volta imparati questi nomi, apri qualunque file e sai già cos'è prima di leggerlo.

| Suffisso / nome | Strato | Cosa fa | Annotazione tipica |
|---|---|---|---|
| …Application.kt | Avvio | Il `main`, accende Spring | `@SpringBootApplication` |
| …Controller.kt | Bordo (in) | Riceve HTTP, traduce in chiamate al Service | `@RestController` |
| …Listener.kt | Bordo (in) | Riceve eventi Kafka | `@KafkaListener` |
| …Service.kt | Logica | Decisioni di business, transazioni | `@Service`, `@Transactional` |
| …Repository.kt | Dati | Interfaccia; Spring genera le query | `: JpaRepository<…>` |
| Entity (Order, Product, …) | Dati | Una riga di tabella | `@Entity`, `@Table` |
| …Event(s).kt | Contratto | Forma JSON dei messaggi Kafka | `data class` |
| EventPublishing.kt | Config | Come/se pubblicare su Kafka | `@Configuration`, `@Bean` |
| Gateways.kt | Bordo (out) | Chiamate HTTP sync verso altri servizi | Client HTTP |
| Config (`application*.yml`, `db/migration/*.sql`) | Infrastruttura | Porte, DB, profili, schema | — |

## Perché i 5 servizi sembrano diversi

Ogni servizio espone solo gli strati che gli servono; nessuno è "diverso a caso".

| Strato | catalog | orders | inventory | payments | notifications |
|---|:---:|:---:|:---:|:---:|:---:|
| Application | ✅ | ✅ | ✅ | ✅ | ✅ |
| Controller (HTTP in) | ✅ | ✅ | ✅ | ✅ | (solo demo) |
| Listener (Kafka in) | — | ✅ | — | — | ✅ |
| Service (logica) | — | ✅ | ✅ | ✅ | — |
| Repository + Entity | ✅ | ✅ | ✅ | ✅ | — |
| Eventi + Publisher | — | ✅ | ✅ | ✅ | — |
| Gateway (HTTP out) | — | ✅ | — | — | — |
| Migrazioni SQL | ✅ | ✅ | ✅ | ✅ | — |

Brevi note sui singoli servizi:

- **catalog**: il più semplice — legge e basta. Non ha `Service`, Kafka o eventi. Flusso tipico: Controller → Repository → Entity.
- **notifications**: l'opposto speculare — nessun HTTP in ingresso (il Controller esiste solo per demo), nessun DB, nessun `Service`. È solo un `Listener` che reagisce agli eventi; progettato per poter sparire senza rompere nulla.
- **payments**: servizio "da manuale" ma piccolo: Controller → Service → Repository → Entity, più eventi. Un solo aggregato (`PaymentAuthorization`) e una regola (idempotenza).
- **inventory**: simile a `payments` ma con due entità (`InventoryItem`, `Reservation`) e un componente di background: `ReservationReconciler` (coroutine che pulisce le prenotazioni scadute).
- **orders**: il più grande (≈14 file) perché è l'orchestratore: tutti gli strati più tre pezzi specifici — `Gateways.kt` (chiama Inventory e Payments via HTTP), il sotto-sistema Outbox (entità OutboxEvent, appender, `OutboxPublisher`) e la saga divisa in due bean (`OrderSaga` e `OrderStateMachine`) per motivi legati a `@Transactional` e self-invocation.

## Come leggere i file (ordine consigliato)

1. `…Application.kt` — conferma il package (1 riga).
2. L'entità principale (es. `Order.kt`, `Product.kt`) — cosa manipola il servizio.
3. Il `Repository` — come accede ai dati e query speciali.
4. Il `Service` — le decisioni di business (idempotenza, compensazioni).
5. Il bordo: `…Controller` (HTTP) e/o `…Listener` (Kafka).
6. I laterali: `…Events.kt` (contratti), `EventPublishing.kt` (uscita Kafka), `Gateways.kt` (uscita HTTP).
7. `resources/`: `application.yml`, `db/migration/` (schema reale del DB).

### Esempio di flusso (orders)

OrdersController (POST → 202) → `OrderService.placeOrder` (salva ordine + outbox in una transazione) → `OutboxPublisher` (relay → Kafka) → `OrderPlacedListener` (riceve l'evento) → `OrderSaga.handle` (reserve → authorize → confirm via Gateways) → `OrderStateMachine` (scrive le transizioni + outbox).

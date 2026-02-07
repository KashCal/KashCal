# Testing

## Running Tests

```bash
# All unit tests
./gradlew testDebugUnitTest

# Specific test class
./gradlew testDebugUnitTest --tests "*EventsDaoTest*"

# With coverage
./gradlew koverHtmlReport
```

## Test Structure

```
app/src/test/kotlin/org/onekash/kashcal/
├── data/
│   ├── db/dao/           # DAO tests (Robolectric)
│   ├── credential/       # Credential manager tests
│   └── repository/       # Repository tests
├── domain/               # Business logic tests
├── sync/                 # Sync strategy tests
└── ui/                   # ViewModel tests
```

## Key Test Libraries

- **JUnit 4** - Test framework
- **MockK** - Kotlin mocking
- **Turbine** - Flow testing
- **Robolectric** - Android framework mocking
- **MockWebServer** - HTTP mocking

## Writing Tests

### DAO Tests
```kotlin
@RunWith(RobolectricTestRunner::class)
class EventsDaoTest {
    private lateinit var database: KashCalDatabase
    private lateinit var eventsDao: EventsDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(...)
        eventsDao = database.eventsDao()
    }
}
```

### Flow Tests
```kotlin
@Test
fun `events flow emits updates`() = runTest {
    eventsDao.getAll().test {
        assertEquals(emptyList(), awaitItem())
        eventsDao.insert(testEvent)
        assertEquals(listOf(testEvent), awaitItem())
    }
}
```

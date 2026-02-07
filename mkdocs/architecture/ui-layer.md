# UI Layer

Jetpack Compose with Material 3 design system.

## ViewModels

- **HomeViewModel** - Main calendar state (700+ lines of state)
- **AccountSettingsViewModel** - Account management

## Key Patterns

### Immutable State
```kotlin
@Immutable
data class HomeUiState(
    val selectedDate: Long,
    val dayEvents: ImmutableList<OccurrenceWithEvent>,
    // ...
)
```

### Flow Observation
```kotlin
eventReader.getOccurrencesForDay(dayCode)
    .distinctUntilChanged()
    .collect { _uiState.update { it.copy(dayEvents = events) } }
```

### Pending Actions
```kotlin
sealed class PendingAction {
    data class ShowEventQuickView(val eventId: Long) : PendingAction()
}

// Consumed via LaunchedEffect
LaunchedEffect(uiState.pendingAction) {
    // Handle action then clear
    viewModel.clearPendingAction()
}
```

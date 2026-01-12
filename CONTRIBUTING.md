# Contributing to KashCal

Thank you for your interest in contributing to KashCal! This document provides guidelines and instructions for contributing.

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- JDK 17 or higher
- Android SDK 35

### Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/KashCal/KashCal.git
   cd KashCal
   ```

2. Build the project:
   ```bash
   ./gradlew assembleDebug
   ```

3. Run tests:
   ```bash
   ./gradlew test
   ```

## How to Contribute

### Reporting Bugs

- Use the [Bug Report](https://github.com/KashCal/KashCal/issues/new?template=bug_report.md) template
- Include your Android version, device, and KashCal version
- Provide steps to reproduce the issue
- Include screenshots or logs if applicable

### Suggesting Features

- Use the [Feature Request](https://github.com/KashCal/KashCal/issues/new?template=feature_request.md) template
- Describe the problem you're trying to solve
- Explain your proposed solution

### Submitting Code

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests (`./gradlew test`)
5. Commit your changes (`git commit -m 'Add amazing feature'`)
6. Push to the branch (`git push origin feature/amazing-feature`)
7. Open a Pull Request

## Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Keep functions small and focused
- Add comments for complex logic

### Architecture Guidelines

KashCal follows Android architecture best practices:

Key principles:
- All data operations go through the domain layer (EventCoordinator, EventReader)
- Never access DAOs directly from ViewModels
- Use Room's Flow for observable data
- Queue all sync operations through PendingOperation

## Testing

- Write unit tests for new functionality
- Ensure existing tests pass before submitting PR
- Test sync functionality with iCloud if modifying sync code

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "*EventCoordinatorTest*"
```

## Pull Request Process

1. Update documentation if needed
2. Add tests for new functionality
3. Ensure CI passes
4. Request review from maintainers

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

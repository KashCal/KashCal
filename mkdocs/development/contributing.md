# Contributing

## Getting Started

1. Fork the repository
2. Clone your fork
3. Create a feature branch
4. Make changes
5. Run tests
6. Submit pull request

## Code Style

- Kotlin coding conventions
- No emojis in code (unless user-requested)
- Prefer editing existing files over creating new ones
- Use domain layer (EventCoordinator) - never access DAOs from ViewModels

## Pull Request Guidelines

- Keep PRs focused on single feature/fix
- Include tests for new functionality
- Update documentation if needed
- Follow existing patterns

## Architecture Rules

- ViewModels → Domain Layer → Data Layer
- Never skip layers
- Use Flow for reactive data
- Use @Transaction for multi-step operations

## Commit Messages

```
feat: Add recurring event editing
fix: Correct timezone handling for all-day events
docs: Update CalDAV sync documentation
test: Add PullStrategy unit tests
```

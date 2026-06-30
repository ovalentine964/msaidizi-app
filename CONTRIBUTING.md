# Contributing to Msaidizi

Thank you for your interest in contributing to Msaidizi! This guide will help you get started.

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- Node.js 18+ and npm
- Docker and Docker Compose
- Git

### Setup

1. **Fork the repository**
   ```bash
   # Fork on GitHub, then clone
   git clone https://github.com/YOUR_USERNAME/msaidizi.git
   cd msaidizi
   ```

2. **Set up backend**
   ```bash
   cd msaidizi-backend
   cp .env.example .env
   npm install
   npm run dev
   ```

3. **Set up Android**
   - Open `msaidizi-android` in Android Studio
   - Update API base URL in `strings.xml`
   - Build and run

## Development Workflow

### Branch Naming

- `feature/description` - New features
- `bugfix/description` - Bug fixes
- `docs/description` - Documentation updates
- `refactor/description` - Code refactoring

### Commit Messages

Use conventional commits:

```
type(scope): description

[optional body]

[optional footer]
```

Types:
- `feat` - New feature
- `fix` - Bug fix
- `docs` - Documentation
- `style` - Formatting
- `refactor` - Code refactoring
- `test` - Adding tests
- `chore` - Maintenance

Examples:
```
feat(whatsapp): add phone validation for Kenyan numbers
fix(onboarding): resolve navigation crash on back press
docs(api): update WhatsApp endpoint documentation
```

### Pull Requests

1. Create a feature branch
2. Make your changes
3. Add tests
4. Update documentation
5. Submit a pull request

## Code Style

### Kotlin (Android)

- Follow Kotlin coding conventions
- Use meaningful variable names
- Add KDoc comments for public APIs
- Use coroutines for async operations

```kotlin
/**
 * Validates a Kenyan phone number.
 *
 * @param raw The raw phone number input
 * @return ValidationResult with normalized number or error
 */
fun validate(raw: String): ValidationResult {
    // Implementation
}
```

### JavaScript (Backend)

- Use ES6+ features
- Follow Airbnb style guide
- Add JSDoc comments for functions
- Use async/await for async operations

```javascript
/**
 * Send a WhatsApp message.
 * 
 * @param {string} phone - Phone number
 * @param {string} message - Message text
 * @returns {Promise<Object>} Message result
 */
async function sendMessage(phone, message) {
    // Implementation
}
```

## Testing

### Android Tests

```bash
# Unit tests
./gradlew test

# Integration tests
./gradlew connectedAndroidTest
```

### Backend Tests

```bash
# Unit tests
npm test

# Integration tests
npm run test:integration

# Coverage
npm run test:coverage
```

### Test Guidelines

- Write tests for all new features
- Maintain >80% code coverage
- Test edge cases and error scenarios
- Use meaningful test names

```kotlin
@Test
fun `valid local Safaricom number returns normalized format`() {
    val result = PhoneValidator.validate("0712345678")
    assertTrue(result is PhoneValidator.ValidationResult.Valid)
    assertEquals("+254712345678", (result as PhoneValidator.ValidationResult.Valid).normalized)
}
```

## Documentation

### Code Documentation

- Add KDoc/JSDoc comments for all public APIs
- Include usage examples
- Document complex algorithms
- Keep documentation up to date

### User Documentation

- Update README.md for new features
- Add screenshots for UI changes
- Update API documentation
- Write migration guides for breaking changes

## Reporting Issues

### Bug Reports

Include:
- Steps to reproduce
- Expected behavior
- Actual behavior
- Environment details
- Screenshots (if applicable)

### Feature Requests

Include:
- Problem description
- Proposed solution
- Use cases
- Mockups (if applicable)

## Code Review

### Review Checklist

- [ ] Code follows style guidelines
- [ ] Tests are included
- [ ] Documentation is updated
- [ ] No security vulnerabilities
- [ ] Performance is acceptable
- [ ] Error handling is proper

### Review Process

1. Submit pull request
2. Automated tests run
3. Code review by maintainers
4. Address feedback
5. Merge when approved

## Release Process

### Versioning

We use [Semantic Versioning](https://semver.org/):

- `MAJOR.MINOR.PATCH`
- MAJOR: Breaking changes
- MINOR: New features
- PATCH: Bug fixes

### Release Steps

1. Update version numbers
2. Update CHANGELOG.md
3. Create release branch
4. Run all tests
5. Create GitHub release
6. Deploy to production

## Community

### Communication

- GitHub Issues - Bug reports and feature requests
- GitHub Discussions - General questions and discussions
- Discord - Real-time chat (link in README)

### Code of Conduct

Please read and follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## Recognition

Contributors will be recognized in:
- README.md contributors section
- Release notes
- GitHub contributors page

## Questions?

If you have questions about contributing, please:
1. Check existing documentation
2. Search GitHub issues
3. Ask in GitHub Discussions
4. Contact maintainers

Thank you for contributing to Msaidizi! 🎉

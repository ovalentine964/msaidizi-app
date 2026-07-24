# Contributing to Msaidizi

Thank you for your interest in contributing to Msaidizi! This document provides guidelines and instructions for contributing.

## 🌟 Ways to Contribute

- **🐛 Bug Reports** — File issues with clear reproduction steps
- **💡 Feature Requests** — Suggest new capabilities
- **🌍 Translations** — Improve Kiswahili and local language coverage
- **🧪 Testing** — Test on budget Android devices
- **📝 Documentation** — Improve guides and code comments
- **🎨 UI/UX** — Material 3 design improvements
- **🧠 AI Tools** — Implement business logic in superagent tools

## 🚀 Getting Started

### Prerequisites

- **JDK 17** (Temurin recommended)
- **Android Studio Ladybug** (2024.2+) or command-line SDK
- **Android SDK 35** (compileSdk)
- **Git** with LFS support

### Setup

```bash
# Fork the repository on GitHub
# Clone your fork
git clone https://github.com/YOUR_USERNAME/msaidizi-app.git
cd msaidizi-app

# Add upstream remote
git remote add upstream https://github.com/ovalentine964/msaidizi-app.git

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew testDebugUnitTest
```

## 📋 Development Workflow

### 1. Create a Branch

```bash
git checkout -b feature/my-feature
# or
git checkout -b fix/my-bugfix
```

### 2. Make Changes

- Follow the [code guidelines](#code-guidelines) below
- Write tests for new functionality
- Update documentation as needed

### 3. Test Your Changes

```bash
# Unit tests
./gradlew testDebugUnitTest

# Lint check
./gradlew lint

# Full CI check
./gradlew check
```

### 4. Commit

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```bash
git commit -m "feat: add voice command for stock check"
git commit -m "fix: crash when model file is corrupted"
git commit -m "docs: update build instructions"
git commit -m "chore: bump Gradle to 8.7"
git commit -m "test: add unit tests for IntentRouter"
```

### 5. Push & PR

```bash
git push origin feature/my-feature
# Open a Pull Request on GitHub
```

## 📐 Code Guidelines

### Kotlin Style

- Follow [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `ktlint` for formatting (run `./gradlew ktlintFormat`)

### Architecture Rules

- **SuperagentHarness** is the single entry point for all AI processing
- Tools are registered in `ToolRegistry` — don't bypass it
- All database access goes through DAOs (no raw SQL)
- Models are loaded lazily and cached as singletons
- Voice pipeline is separate from the text pipeline (they converge at `processInput`)

### UI Guidelines

- Use **Jetpack Compose** for all UI (no XML layouts)
- Follow **Material 3** design system
- Support both **Kiswahili** and **English** — use `strings.xml`
- Test on **small screens** (5" minimum) and **low RAM** (2GB)

### Testing

- Unit tests for all business logic in tools
- Integration tests for database operations
- UI tests for critical user flows
- Test on an emulator with 2GB RAM limit

## 🏷️ Pull Request Guidelines

### PR Title

Use the same conventional commit format:
- `feat: add inventory low-stock alerts`
- `fix: voice pipeline crash on ARM32`

### PR Description

Include:
1. **What** does this PR do?
2. **Why** is this change needed?
3. **How** was it tested?
4. **Screenshots** (for UI changes)
5. **Device tested on** (for device-specific changes)

### Review Process

1. All PRs require at least one review
2. CI must pass (build + tests + lint)
3. No merge conflicts
4. Documentation updated if needed

## 🐛 Reporting Bugs

Use the [GitHub Issue Tracker](../../issues/new?template=bug_report.md) with:

1. **Device info**: Model, Android version, RAM
2. **Steps to reproduce**: Clear numbered steps
3. **Expected behavior**: What should happen
4. **Actual behavior**: What actually happens
5. **Logs**: `adb logcat` output if possible

## 💡 Feature Requests

Use the [GitHub Issue Tracker](../../issues/new?template=feature_request.md) with:

1. **Problem**: What problem does this solve?
2. **Solution**: Your proposed solution
3. **Alternatives**: Other approaches considered
4. **Users**: Who benefits from this?

## 📜 Code of Conduct

Please read and follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## 📄 License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).

## 🙏 Thank You

Your contributions help make business tools accessible to every entrepreneur in Kenya and across Africa. Asante sana! 🇰🇪

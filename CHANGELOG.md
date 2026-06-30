# Changelog

All notable changes to Msaidizi will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- WhatsApp connection onboarding step
- Phone validation for Kenyan numbers (07XX, 01XX, +254)
- WhatsApp verification flow with polling
- Report generation (daily and weekly)
- WhatsApp command handlers (ripoti, mauzo, faida, etc.)
- Multi-language support (Swahili, Sheng, English)
- Report scheduling (morning, afternoon, evening)
- Share functionality
- OpenWA integration
- Backend API endpoints
- Database schema
- Docker deployment configuration
- Nginx reverse proxy setup
- Comprehensive documentation

### Changed
- Updated onboarding flow to include WhatsApp connection
- Enhanced phone validation with carrier detection
- Improved error handling with localized messages

### Fixed
- Phone number normalization edge cases
- Verification timeout handling
- Rate limiting configuration

## [1.0.0] - 2024-01-15

### Added
- Initial release
- Basic onboarding flow
- Business discovery phase
- Personality and preferences
- First use tutorial

## [0.1.0] - 2024-01-01

### Added
- Project initialization
- Basic project structure
- Development environment setup

---

## Release Notes

### Version 1.0.0

**WhatsApp Connection Onboarding**

This release introduces the WhatsApp connection step in the onboarding flow. Workers can now connect their WhatsApp numbers to receive daily business reports, share the app, and query their data via WhatsApp commands.

**Key Features:**
- 📱 WhatsApp connection during onboarding
- 📊 Daily and weekly business reports
- 💬 WhatsApp command interface
- 🌍 Multi-language support (Swahili, Sheng, English)
- ⏰ Configurable report scheduling
- 📤 Share functionality

**Technical Highlights:**
- Kenyan phone number validation (Safaricom, Airtel, Telkom)
- OpenWA integration for WhatsApp messaging
- RESTful API with JWT authentication
- PostgreSQL database with Redis caching
- Docker-based deployment
- Comprehensive test coverage

**Breaking Changes:**
- None (initial release)

**Migration Guide:**
- N/A (initial release)

**Known Issues:**
- OpenWA requires manual QR code scanning for initial setup
- Report generation uses mock data (to be connected to real transaction data)
- Group messages not yet supported

**Upcoming Features:**
- SMS fallback for WhatsApp failures
- Voice note reports
- Image reports (charts)
- M-Pesa integration
- AI-powered business insights

---

### Version 0.1.0

**Project Initialization**

Initial project setup with basic structure and development environment.

**Key Features:**
- Basic project structure
- Development environment configuration
- Initial documentation

**Technical Highlights:**
- Android project setup with Kotlin
- Node.js backend with Express
- Development scripts and tools

**Breaking Changes:**
- None (initial release)

**Migration Guide:**
- N/A (initial release)

**Known Issues:**
- No functional features yet
- Documentation incomplete

**Upcoming Features:**
- WhatsApp connection onboarding
- Business report generation
- Multi-language support

# Msaidizi Architecture Overview

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Msaidizi System                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    Mobile Application                            │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │                  Android App (Kotlin)                    │    │    │
│  │  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐       │    │    │
│  │  │  │ Intro   │ │Business │ │WhatsApp │ │Persona  │       │    │    │
│  │  │  │ Phase   │ │Discovery│ │Connect  │ │& Prefs  │       │    │    │
│  │  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘       │    │    │
│  │  │                                                         │    │    │
│  │  │  ┌─────────────────────────────────────────────────┐    │    │    │
│  │  │  │           Core Components                        │    │    │    │
│  │  │  │  - OnboardingActivity                           │    │    │    │
│  │  │  │  - WhatsAppConnectionStep (ViewModel)           │    │    │    │
│  │  │  │  - WhatsAppVerificationManager                  │    │    │    │
│  │  │  │  - PhoneValidator                               │    │    │    │
│  │  │  └─────────────────────────────────────────────────┘    │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                               │                                          │
│                               │ HTTPS                                    │
│                               ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    Backend Services                              │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │                 API Gateway (Nginx)                      │    │    │
│  │  │  - Rate limiting                                        │    │    │
│  │  │  - SSL termination                                      │    │    │
│  │  │  - Load balancing                                       │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  │                               │                                  │    │
│  │                               ▼                                  │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │              Application Server (Express.js)             │    │    │
│  │  │  ┌─────────────────────────────────────────────────┐    │    │    │
│  │  │  │              WhatsApp Routes                     │    │    │    │
│  │  │  │  - POST /connect                                 │    │    │    │
│  │  │  │  - POST /verify                                  │    │    │    │
│  │  │  │  - GET  /status                                  │    │    │    │
│  │  │  │  - POST /send-report                             │    │    │    │
│  │  │  └─────────────────────────────────────────────────┘    │    │    │
│  │  │  ┌─────────────────────────────────────────────────┐    │    │    │
│  │  │  │              Services                            │    │    │    │
│  │  │  │  - WhatsAppService                              │    │    │    │
│  │  │  │  - ReportGenerator                              │    │    │    │
│  │  │  │  - ReportCronJob                                │    │    │    │
│  │  │  └─────────────────────────────────────────────────┘    │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  │                               │                                  │    │
│  │                               ▼                                  │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │              OpenWA Integration                          │    │    │
│  │  │  ┌─────────────────────────────────────────────────┐    │    │    │
│  │  │  │              MessageHandler                      │    │    │    │
│  │  │  │  - "ripoti" → handleReportRequest()             │    │    │    │
│  │  │  │  - "mauzo"  → handleSalesRequest()              │    │    │    │
│  │  │  │  - "faida"  → handleProfitRequest()             │    │    │    │
│  │  │  │  - "msaada" → handleHelpRequest()               │    │    │    │
│  │  │  │  - "shiriki" → handleShareRequest()             │    │    │    │
│  │  │  └─────────────────────────────────────────────────┘    │    │    │
│  │  │  ┌─────────────────────────────────────────────────┐    │    │    │
│  │  │  │              ReportCronJob                       │    │    │    │
│  │  │  │  - Morning: 8:00 AM EAT                         │    │    │    │
│  │  │  │  - Afternoon: 1:00 PM EAT                       │    │    │    │
│  │  │  │  - Evening: 6:00 PM EAT                         │    │    │    │
│  │  │  │  - Weekly: Sunday 6:00 PM EAT                   │    │    │    │
│  │  │  └─────────────────────────────────────────────────┘    │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                               │                                          │
│                               ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    Data Layer                                    │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │              PostgreSQL Database                         │    │    │
│  │  │  - users                                               │    │    │
│  │  │  - whatsapp_connections                                │    │    │
│  │  │  - verifications                                       │    │    │
│  │  │  - transactions                                        │    │    │
│  │  │  - products                                            │    │    │
│  │  │  - daily_summaries                                     │    │    │
│  │  │  - weekly_summaries                                    │    │    │
│  │  │  - whatsapp_messages                                   │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │              Redis Cache                                 │    │    │
│  │  │  - Session data                                        │    │    │
│  │  │  - Rate limiting counters                              │    │    │
│  │  │  - Temporary verification data                         │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                               │                                          │
│                               ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    External Services                             │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │              WhatsApp Business API                       │    │    │
│  │  │  - Send messages                                       │    │    │
│  │  │  - Receive messages                                    │    │    │
│  │  │  - Delivery receipts                                   │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │              GitHub Releases                             │    │    │
│  │  │  - App downloads                                       │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

## Component Details

### Mobile Application

#### OnboardingActivity
- Hosts the entire onboarding flow
- Manages shared state across fragments
- Provides API client to fragments

#### WhatsAppConnectionStep (ViewModel)
- Manages UI state for WhatsApp connection
- Handles phone validation
- Coordinates with WhatsAppVerificationManager
- Manages navigation between phases

#### WhatsAppVerificationManager
- Handles API calls to backend
- Implements retry logic with exponential backoff
- Polls for verification status
- Manages timeouts and errors

#### PhoneValidator
- Validates Kenyan phone numbers
- Normalizes to international format
- Detects carrier (Safaricom, Airtel, Telkom)
- Formats for display

### Backend Services

#### WhatsApp Routes
- POST /api/v1/whatsapp/connect
- POST /api/v1/whatsapp/verify
- GET /api/v1/whatsapp/verify/:id/status
- GET /api/v1/whatsapp/connection/:userId
- POST /api/v1/whatsapp/disconnect/:userId
- POST /api/v1/whatsapp/send-report

#### WhatsAppService
- Business logic for WhatsApp operations
- Verification management
- Report generation
- Connection state management

#### ReportGenerator
- Generates daily and weekly reports
- Supports multiple languages (Swahili, Sheng, English)
- Formats data with business insights
- Includes tips and recommendations

#### ReportCronJob
- Scheduled report delivery
- Configurable time slots
- Rate limiting for message delivery
- Error handling and retry

### OpenWA Integration

#### OpenWAClient
- Wrapper around OpenWA library
- Message sending
- Number verification
- Connection management

#### MessageHandler
- Routes incoming messages to handlers
- Supports multiple languages
- Command parsing and execution
- Response generation

#### ReportCronJob
- Scheduled report delivery
- Configurable time slots
- Rate limiting
- Error handling

### Data Layer

#### PostgreSQL Database
- User management
- WhatsApp connections
- Verification records
- Transaction history
- Report summaries

#### Redis Cache
- Session data
- Rate limiting counters
- Temporary verification data
- Frequently accessed data

## Data Flow

### Onboarding Flow

```
User enters phone number
    ↓
PhoneValidator validates and normalizes
    ↓
WhatsAppConnectionStep sends to backend
    ↓
WhatsAppService creates verification record
    ↓
OpenWAClient sends welcome message
    ↓
User receives WhatsApp message
    ↓
User taps "Nimepokea!"
    ↓
WhatsAppConnectionStep confirms receipt
    ↓
WhatsAppService marks as connected
    ↓
User proceeds to next onboarding step
```

### Report Delivery Flow

```
ReportCronJob triggers at scheduled time
    ↓
WhatsAppService gets connected users
    ↓
ReportGenerator generates reports
    ↓
OpenWAClient sends reports via WhatsApp
    ↓
Users receive reports
    ↓
Users can query with commands
    ↓
MessageHandler routes commands
    ↓
WhatsAppService generates responses
    ↓
OpenWAClient sends responses
```

## Security Architecture

### Authentication
- JWT tokens for API authentication
- Token expiration and refresh
- Secure token storage

### Authorization
- Role-based access control
- User-specific data access
- Admin privileges

### Data Protection
- Phone number masking in logs
- Encrypted data transmission (HTTPS)
- Secure database connections

### Rate Limiting
- Global rate limiting
- Per-endpoint rate limiting
- Per-user rate limiting

## Scalability

### Horizontal Scaling
- Stateless backend services
- Load balancing with Nginx
- Database read replicas

### Vertical Scaling
- Connection pooling
- Caching strategies
- Background job processing

### Performance Optimization
- Database indexing
- Query optimization
- Response compression
- CDN for static assets

## Monitoring

### Health Checks
- Backend health endpoint
- OpenWA connection status
- Database connectivity
- Redis connectivity

### Logging
- Structured logging
- Log aggregation
- Error tracking
- Performance metrics

### Alerting
- Critical error alerts
- Performance degradation alerts
- Capacity planning alerts
- Security incident alerts

## Future Enhancements

### Short Term
- SMS fallback for WhatsApp failures
- Voice note reports
- Image reports (charts)
- Push notifications

### Medium Term
- Multi-language support expansion
- Offline report caching
- Group reports
- M-Pesa integration

### Long Term
- AI-powered business insights
- Predictive analytics
- Automated inventory management
- Multi-location support

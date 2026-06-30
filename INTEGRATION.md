# Msaidizi WhatsApp Integration Guide

## Overview

This guide explains how to integrate the WhatsApp connection step into the Msaidizi onboarding flow.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Msaidizi Android App                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                  OnboardingActivity                      │    │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐       │    │
│  │  │ Phase 1 │ │ Phase 2 │ │ Phase 3 │ │ Phase 4 │       │    │
│  │  │ Intro   │ │Business │ │WhatsApp │ │Persona  │       │    │
│  │  └─────────┘ └─────────┘ └────┬────┘ └─────────┘       │    │
│  │                               │                          │    │
│  │  ┌─────────────────────────────┼──────────────────────┐ │    │
│  │  │     WhatsAppConnectionStep  │  (ViewModel)         │ │    │
│  │  │  ┌──────────────────────────┼────────────────────┐ │ │    │
│  │  │  │  WhatsAppVerificationManager                  │ │ │    │
│  │  │  │  - Phone validation                           │ │ │    │
│  │  │  │  - API calls with retry                       │ │ │    │
│  │  │  │  - Polling for confirmation                   │ │ │    │
│  │  │  └───────────────────────────────────────────────┘ │ │    │
│  │  └─────────────────────────────────────────────────────┘ │    │
│  └─────────────────────────────────────────────────────────┘    │
│                               │                                  │
│                               ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    MsaidiziApi (Retrofit)                 │    │
│  │  POST /api/v1/whatsapp/connect                           │    │
│  │  POST /api/v1/whatsapp/verify                            │    │
│  │  GET  /api/v1/whatsapp/verify/:id/status                 │    │
│  └─────────────────────────────────────────────────────────┘    │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTPS
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Msaidizi Backend                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                 WhatsApp Routes                          │    │
│  │  - POST /connect → WhatsAppService.connect()             │    │
│  │  - POST /verify  → WhatsAppService.verify()              │    │
│  │  - GET  /status  → WhatsAppService.getStatus()           │    │
│  └─────────────────────────────────────────────────────────┘    │
│                               │                                  │
│                               ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                 WhatsAppService                          │    │
│  │  - checkNumberOnWhatsApp()                               │    │
│  │  - sendVerificationMessage()                             │    │
│  │  - generateReport()                                      │    │
│  │  - sendReport()                                          │    │
│  └─────────────────────────────────────────────────────────┘    │
│                               │                                  │
│                               ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    OpenWA Integration                     │    │
│  │  ┌─────────────────────────────────────────────────────┐ │    │
│  │  │  MessageHandler                                      │ │    │
│  │  │  - "ripoti" → handleReportRequest()                  │ │    │
│  │  │  - "mauzo"  → handleSalesRequest()                   │ │    │
│  │  │  - "faida"  → handleProfitRequest()                  │ │    │
│  │  │  - "msaada" → handleHelpRequest()                    │ │    │
│  │  └─────────────────────────────────────────────────────┘ │    │
│  │  ┌─────────────────────────────────────────────────────┐ │    │
│  │  │  ReportCronJob                                       │ │    │
│  │  │  - Morning: 8:00 AM EAT                              │ │    │
│  │  │  - Afternoon: 1:00 PM EAT                            │ │    │
│  │  │  - Evening: 6:00 PM EAT                              │ │    │
│  │  │  - Weekly: Sunday 6:00 PM EAT                        │ │    │
│  │  └─────────────────────────────────────────────────────┘ │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

## Integration Steps

### 1. Add Dependencies

**Android (build.gradle.kts):**
```kotlin
dependencies {
    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.5")
    
    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    
    // Material Design
    implementation("com.google.android.material:material:1.10.0")
}
```

**Backend (package.json):**
```json
{
  "dependencies": {
    "express": "^4.18.2",
    "@open-wa/wa-automate": "^4.66.0",
    "jsonwebtoken": "^9.0.2",
    "node-cron": "^3.0.3",
    "uuid": "^9.0.0"
  }
}
```

### 2. Add to Navigation Graph

```xml
<!-- nav_onboarding.xml -->
<fragment
    android:id="@+id/whatsappConnectionFragment"
    android:name="com.msaidizi.app.onboarding.WhatsAppConnectionStepFragment"
    android:label="WhatsApp Connection"
    tools:layout="@layout/fragment_whats_app_connection">

    <action
        android:id="@+id/action_whatsapp_to_personality"
        app:destination="@id/personalityFragment" />
</fragment>
```

### 3. Update OnboardingActivity

```kotlin
class OnboardingActivity : AppCompatActivity() {
    
    // Shared onboarding session data
    val onboardingData = OnboardingSessionData()
    
    // WhatsApp step ViewModel
    val whatsappStep: WhatsAppConnectionStep by lazy {
        ViewModelProvider(this, WhatsAppStepFactory(application, api, onboardingData))
            .get(WhatsAppConnectionStep::class.java)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... setup code
    }
}
```

### 4. Add Layout Resources

Copy the layout files:
- `fragment_whats_app_connection.xml`
- `activity_onboarding.xml`

### 5. Add Navigation Actions

Update your navigation graph to include the WhatsApp connection step between Business Discovery and Personality phases.

## Configuration

### Android

Update `strings.xml`:
```xml
<string name="api_base_url">https://api.msaidizi.app/</string>
```

### Backend

Update `.env`:
```bash
OPENWA_API_URL=http://localhost:8080
OPENWA_API_KEY=your-api-key
OPENWA_SESSION_ID=msaidizi
```

## Testing

### Unit Tests

```bash
# Backend tests
cd msaidizi-backend
npm test

# Android tests
./gradlew test
```

### Integration Tests

```bash
# Start services
docker-compose up -d

# Run integration tests
npm run test:integration
```

### Manual Testing

1. Start the app
2. Complete onboarding phases 1 and 2
3. Enter a valid Kenyan phone number
4. Verify WhatsApp message is received
5. Tap "Nimepokea!" to confirm
6. Verify connection is established

## Troubleshooting

### WhatsApp Message Not Sending

1. Check OpenWA is connected:
   ```bash
   curl http://localhost:8080/session/msaidizi/status
   ```

2. Check backend logs:
   ```bash
   docker-compose logs backend
   ```

3. Verify phone number is on WhatsApp

### Verification Timeout

1. Check network connectivity
2. Verify OpenWA is not rate-limited
3. Check if WhatsApp is blocking messages

### Polling Issues

1. Check backend is accessible from Android app
2. Verify API base URL is correct
3. Check for CORS issues (if testing on web)

## Security Considerations

1. **Phone Validation**: Always validate and normalize phone numbers
2. **Rate Limiting**: Implement rate limiting on all endpoints
3. **JWT Tokens**: Use secure JWT tokens for authentication
4. **Input Sanitization**: Sanitize all user inputs
5. **HTTPS**: Use HTTPS in production
6. **Webhook Signatures**: Verify webhook signatures

## Performance Optimization

1. **Connection Pooling**: Use connection pooling for database
2. **Caching**: Cache frequently accessed data
3. **Background Processing**: Use queues for report generation
4. **Pagination**: Paginate large result sets
5. **Compression**: Enable gzip compression

## Monitoring

1. **Health Checks**: Monitor `/health` endpoint
2. **Logs**: Centralize logs for analysis
3. **Metrics**: Track key metrics (messages sent, errors, etc.)
4. **Alerts**: Set up alerts for critical errors

## Future Enhancements

- [ ] SMS fallback for WhatsApp failures
- [ ] Voice note reports
- [ ] Image reports (charts)
- [ ] Multi-language support
- [ ] Offline report caching
- [ ] Push notifications
- [ ] Group reports
- [ ] M-Pesa integration

# Msaidizi Quick Start Guide

## For Developers

### 1. Clone the Repository

```bash
git clone <repository-url>
cd msaidizi
```

### 2. Start Backend

```bash
cd msaidizi-backend

# Copy environment file
cp .env.example .env

# Edit .env with your settings
nano .env

# Install dependencies
npm install

# Start development server
npm run dev
```

### 3. Configure OpenWA

```bash
# OpenWA will start on port 8080
# Scan QR code with WhatsApp
curl http://localhost:8080/session/msaidizi/qr
```

### 4. Open Android Project

```bash
# Open in Android Studio
# File → Open → Select msaidizi-android folder

# Update API base URL in strings.xml
# <string name="api_base_url">http://10.0.2.2:3000/</string>
```

### 5. Run the App

```bash
# Build and run
./gradlew installDebug
```

## For Product Managers

### Onboarding Flow

1. **Introduction** (30 seconds)
   - Msaidizi asks for worker's name
   - Worker names Msaidizi

2. **Business Discovery** (1 minute)
   - Business description
   - Location
   - Operating hours

3. **WhatsApp Connection** (1 minute)
   - Enter phone number
   - Receive WhatsApp message
   - Confirm receipt

4. **Preferences** (30 seconds)
   - Speed (fast/slow)
   - Language (Swahili/Sheng/English)
   - Report time (morning/afternoon/evening)

5. **First Use** (1 minute)
   - Guided first transaction
   - Success celebration

**Total: ~4 minutes**

### Key Metrics

- **Connection Rate**: % of users who connect WhatsApp
- **Report Engagement**: % of users who read reports
- **Command Usage**: Most used WhatsApp commands
- **Retention**: % of users still active after 7 days

## For QA

### Test Cases

#### Phone Validation

| Input | Expected | Status |
|-------|----------|--------|
| 0712345678 | +254712345678 | ✅ Pass |
| 0112345678 | +254112345678 | ✅ Pass |
| +254712345678 | +254712345678 | ✅ Pass |
| 254712345678 | +254712345678 | ✅ Pass |
| 712345678 | +254712345678 | ✅ Pass |
| 0712 345 678 | +254712345678 | ✅ Pass |
| 0712-345-678 | +254712345678 | ✅ Pass |
| 123 | Invalid | ✅ Pass |
| abc | Invalid | ✅ Pass |
| (empty) | Invalid | ✅ Pass |

#### WhatsApp Connection

| Step | Action | Expected | Status |
|------|--------|----------|--------|
| 1 | Enter valid phone | Show confirmation | ✅ Pass |
| 2 | Confirm phone | Send WhatsApp message | ✅ Pass |
| 3 | Receive message | Show "Nimepokea" button | ✅ Pass |
| 4 | Tap "Nimepokea" | Show success | ✅ Pass |
| 5 | Invalid phone | Show error | ✅ Pass |
| 6 | Network error | Show retry option | ✅ Pass |
| 7 | Timeout | Show timeout message | ✅ Pass |

#### WhatsApp Commands

| Command | Expected Response | Status |
|---------|-------------------|--------|
| ripoti | Daily report | ✅ Pass |
| mauzo | Sales summary | ✅ Pass |
| faida | Profit summary | ✅ Pass |
| wiki | Weekly report | ✅ Pass |
| msaada | Help message | ✅ Pass |
| shiriki | Share link | ✅ Pass |
| simama | Unsubscribe | ✅ Pass |
| anza | Resubscribe | ✅ Pass |
| kiswahili | Switch language | ✅ Pass |
| sheng | Switch language | ✅ Pass |
| english | Switch language | ✅ Pass |
| hali | Status | ✅ Pass |

### Test Environment

```bash
# Backend
API_URL=http://localhost:3000

# OpenWA
OPENWA_URL=http://localhost:8080

# Test phone numbers
TEST_PHONE=0712345678
TEST_PHONE_INVALID=123
```

## For Support

### Common Issues

**Q: WhatsApp message not arriving**
A: Check if OpenWA is connected. Run: `curl http://localhost:8080/session/msaidizi/status`

**Q: Phone number not accepted**
A: Use Kenyan format: 0712345678 or +254712345678

**Q: Reports not arriving**
A: Check report time in preferences. Verify WhatsApp connection is active.

**Q: Commands not working**
A: Send "msaada" to see available commands. Check language setting.

### Contact

- **Technical Issues**: dev@msaidizi.app
- **Product Questions**: product@msaidizi.app
- **General Support**: support@msaidizi.app

## Resources

- [API Documentation](API.md)
- [Deployment Guide](DEPLOYMENT.md)
- [Integration Guide](INTEGRATION.md)
- [Architecture Overview](ARCHITECTURE.md)

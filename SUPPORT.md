# Support

## Getting Help

If you need help with Msaidizi, there are several ways to get support:

### Documentation

- [README.md](README.md) - Project overview and setup
- [QUICKSTART.md](QUICKSTART.md) - Quick start guide
- [API.md](API.md) - API documentation
- [DEPLOYMENT.md](DEPLOYMENT.md) - Deployment guide
- [INTEGRATION.md](INTEGRATION.md) - Integration guide
- [ARCHITECTURE.md](ARCHITECTURE.md) - Architecture overview

### Community Support

- **GitHub Issues**: [Report bugs and request features](https://github.com/msaidizi/msaidizi/issues)
- **GitHub Discussions**: [Ask questions and discuss](https://github.com/msaidizi/msaidizi/discussions)
- **Discord**: [Join our community](https://discord.gg/msaidizi)

### Professional Support

For professional support, consulting, or custom development:

- **Email**: [support@msaidizi.app](mailto:support@msaidizi.app)
- **Website**: [https://msaidizi.app/support](https://msaidizi.app/support)

## FAQ

### General

**Q: What is Msaidizi?**
A: Msaidizi is a business assistant that helps small business owners in Kenya track their sales, profits, and inventory via WhatsApp.

**Q: How does it work?**
A: Msaidizi uses voice input to record transactions, generates daily and weekly reports, and delivers them via WhatsApp.

**Q: Is it free?**
A: Yes, Msaidizi is free and open-source.

**Q: What languages does it support?**
A: Msaidizi supports Swahili, Sheng (Kenyan slang), and English.

### WhatsApp Connection

**Q: How do I connect my WhatsApp?**
A: During onboarding, enter your phone number and follow the prompts. You'll receive a verification message on WhatsApp.

**Q: Why isn't my WhatsApp message arriving?**
A: Check that:
1. Your phone has internet connectivity
2. WhatsApp is installed and active
3. You entered the correct phone number
4. The backend server is running

**Q: Can I change my WhatsApp number?**
A: Yes, you can update your WhatsApp number in the app settings.

**Q: How do I stop receiving reports?**
A: Send "simama" to the Msaidizi WhatsApp number to unsubscribe.

### Reports

**Q: When do I receive reports?**
A: Reports are sent at your preferred time (morning, afternoon, or evening).

**Q: Can I get reports in my language?**
A: Yes, you can choose between Swahili, Sheng, and English.

**Q: How do I get a report now?**
A: Send "ripoti" to the Msaidizi WhatsApp number.

**Q: Can I get weekly reports?**
A: Yes, send "wiki" to get a weekly summary.

### Technical

**Q: What are the system requirements?**
A: 
- Android: Android 8.0+ (API 26+)
- Backend: Node.js 18+, PostgreSQL 15+, Redis 7+
- OpenWA: WhatsApp Web compatible browser

**Q: How do I set up the development environment?**
A: See [QUICKSTART.md](QUICKSTART.md) for detailed setup instructions.

**Q: How do I deploy to production?**
A: See [DEPLOYMENT.md](DEPLOYMENT.md) for deployment instructions.

**Q: How do I contribute?**
A: See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

## Troubleshooting

### Backend Issues

**Problem: Server won't start**
- Check if port 3000 is available
- Verify environment variables are set
- Check database connection

**Problem: Database connection failed**
- Verify PostgreSQL is running
- Check database credentials
- Ensure database exists

**Problem: OpenWA not connecting**
- Check if OpenWA is running
- Verify API key is correct
- Check WhatsApp Web session

### Mobile App Issues

**Problem: App crashes on startup**
- Check Android version compatibility
- Verify API base URL is correct
- Check network connectivity

**Problem: Phone validation fails**
- Use Kenyan format: 0712345678
- Include country code: +254712345678
- Remove spaces and special characters

**Problem: WhatsApp connection timeout**
- Check internet connectivity
- Verify phone number is correct
- Try again later

### WhatsApp Issues

**Problem: Messages not delivering**
- Check WhatsApp is installed
- Verify phone number is on WhatsApp
- Check internet connectivity

**Problem: Commands not working**
- Send "msaada" for help
- Check language setting
- Verify connection is active

**Problem: Reports not arriving**
- Check report time preference
- Verify WhatsApp connection
- Send "hali" to check status

## Contact

### Technical Support

- **Email**: [support@msaidizi.app](mailto:support@msaidizi.app)
- **GitHub Issues**: [Create an issue](https://github.com/msaidizi/msaidizi/issues)

### Security Issues

- **Email**: [security@msaidizi.app](mailto:security@msaidizi.app)
- **PGP Key**: [Available on request]

### Business Inquiries

- **Email**: [business@msaidizi.app](mailto:business@msaidizi.app)
- **Website**: [https://msaidizi.app](https://msaidizi.app)

### Social Media

- **Twitter**: [@msaidizi](https://twitter.com/msaidizi)
- **LinkedIn**: [Msaidizi](https://linkedin.com/company/msaidizi)
- **Facebook**: [Msaidizi](https://facebook.com/msaidizi)

## Feedback

We value your feedback! Please share your thoughts, suggestions, and feature requests:

- **GitHub Discussions**: [Share feedback](https://github.com/msaidizi/msaidizi/discussions)
- **Email**: [feedback@msaidizi.app](mailto:feedback@msaidizi.app)
- **Survey**: [Take our survey](https://survey.msaidizi.app)

## Contributing

Want to contribute to Msaidizi? See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Msaidizi is licensed under the MIT License. See [LICENSE](LICENSE) for details.

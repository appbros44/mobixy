# Mobixy
Turn any Android device into a secure mobile proxy using Kotlin, Netty SOCKS5, foreground services, root-based IP rotation, and a Node.js + MySQL backend for authentication and control. Ideal for SEO, scraping, and proxy businesses.

# 📡 Android Mobile SOCKS5 Proxy Server

Turn an Android device into a **real mobile SOCKS5 proxy** using **Kotlin + Netty**, with
**username/password authentication**, **foreground service stability**, and **IP rotation**.
Designed for **SEO, web scraping, automation**, and **paid mobile proxy businesses**.

---

## 🚀 Features

- ✅ SOCKS5 proxy server (Netty)
- 🔐 Username / Password authentication
- 📱 Runs as Android Foreground Service
- 🔁 Mobile IP rotation (Airplane toggle – rooted devices)
- 🌐 Real 4G / 5G mobile IP
- 🧠 Backend control with Node.js + MySQL
- 📊 Device & user management
- ⚡ Low latency, high compatibility

---

## 🧠 Architecture Overview
```
Client (Scraper / SEO Tool)
        |
      SOCKS5 Proxy
        |
  Android App (Kotlin)
    ├── Netty SOCKS5 Server
    ├── Auth Handler
    ├── Foreground Service
    └── IP Rotation Engine
        |
  Mobile Network (4G / 5G)
```

---

## 🧱 Tech Stack

### Android App
- Kotlin
- Netty (SOCKS5)
- Foreground Service
- Root shell access (for IP rotation)

### Backend
- Node.js (Express)
- MySQL
- REST APIs (WebSocket optional)

---

## 📁 Project Structure
```
android-app/
├── ProxyService.kt
├── SocksServer.kt
├── SocksInitializer.kt
├── AuthHandler.kt
├── CommandHandler.kt
├── RelayHandler.kt
├── AuthManager.kt

backend/
├── index.js
├── db.js
├── routes/
│ ├── auth.js
│ ├── device.js
│ ├── rotate.js
```
---

## 🔐 Proxy Authentication

SOCKS5 authentication using **username/password**

Proxy format:
IP:PORT:USERNAME:PASSWORD


Compatible with:
- Python (requests / aiohttp / scrapy)
- Puppeteer / Playwright
- Scrapebox / GSA
- Custom SEO bots

---

## 🔁 IP Rotation (Root Required)

The proxy supports **real mobile IP rotation** using airplane mode toggling.

Rotation can be triggered by:
- Time interval
- API call
- Request count
- Manual admin action

> ⚠️ Root access is **strongly recommended** for reliable IP rotation.

---

## 🌐 Backend Capabilities

- User management
- Device registration
- Proxy authentication
- IP rotation trigger
- Plan expiry control
- Usage logging (optional)

---

## ⚠️ Important Notes

- This project is intended for **SEO, testing, and research purposes**
- Avoid spam, abuse, or illegal activity
- Secure your proxy with authentication
- Monitor bandwidth and connections
- Follow local ISP and legal regulations

---

## 🛣️ Roadmap

- [ ] WebSocket-based real-time control
- [ ] Admin dashboard
- [ ] Bandwidth & usage limits
- [ ] Cloudflare Tunnel support
- [ ] Proxy pool & load balancing
- [ ] Non-root fallback rotation

---

## 🧑‍💻 Ideal Use Cases

- SEO rank tracking
- SERP scraping
- Web automation
- App testing
- Paid mobile proxy services

---

## 📜 License

MIT License  
Use at your own responsibility.

---

## 🤝 Contributions

Pull requests are welcome.
For major changes, please open an issue first to discuss.

---

## ⭐ Support

If this project helped you, consider giving it a ⭐  
It helps the project grow 🚀




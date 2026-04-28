# Arthsetu - Personal Financial Management & Wealth Planning Platform

**Arthsetu** (meaning "bridge" in Sanskrit) is a comprehensive personal financial management and wealth planning platform designed for the Codesphere26 Hackathon. It bridges the gap between daily expense tracking and strategic financial planning by automatically capturing financial transactions, providing risk analysis, and simulating financial scenarios to help users make informed financial decisions.

> **Tagline**: Bridge Your Path to Financial Freedom

---

## 🎯 Overview

Arthsetu is an **Android + Firebase** application that transforms how Indians manage their finances by:
- 📱 **Automatically capturing transactions** from bank SMS and PDF statements
- 🏦 **Intelligent categorization** using a 4-tier fallback system
- 💾 **Smart deduplication** to prevent duplicate transaction entries
- 📊 **Real-time financial risk scoring** based on income and spending patterns
- 🎰 **What-if scenario simulators** for job loss, medical emergencies, income drops, market crashes, and rate hikes
- 💰 **Comprehensive wealth tracking** (assets, liabilities, net worth calculation)
- 🎯 **Financial goal management** with AI-driven insights
- 📈 **Rich analytics dashboards** with customizable charts and expense tracking
- 🔔 **Smart push notifications** via Firebase Cloud Messaging for spending alerts and limit breaches

---

## ✨ Key Features

### 1. **Multi-Source Transaction Capture**
- **Real-time SMS Parsing**: Automatically intercepts and parses bank SMS from 50+ Indian banks (SBI, HDFC, ICICI, Axis, Kotak, Yes Bank, IDFC, etc.)
- **Bank Statement PDF Import**: Uploads and parses PDF statements with OCR fallback (ML Kit) for scanned documents
- **Manual Entry**: Quick transaction logging via UI form
- **Automatic Duplicate Detection**: Advanced 5-layer deduplication strategy to prevent duplicate entries

### 2. **Intelligent Transaction Categorization**
4-tier smart categorization system:
1. User-cached categories (fastest)
2. Cloud merchant database lookups
3. Hardcoded merchant rules (50+ merchants pre-configured)
4. User manual assignment with FCM notifications

### 3. **Financial Risk Scoring Engine**
Calculates a 0-100 risk score based on:
- **Expense Ratio** (0-50 points)
- **Low Savings Rate** (0-30 points)
- **High Spending Patterns** (0-20 points)

**Risk Levels**: Low (<40) | Medium (40-69) | High (≥70)

### 4. **Five Advanced Financial Scenario Simulators**
Explore "what-if" financial situations:
- 💼 **Job Loss**: Survival runway, EMI strategies, expense-cutting plans
- 🏥 **Medical Emergency**: Insurance gaps analysis, debt modeling
- 📉 **Income Drop**: Salary cut impact analysis, expense adjustments
- 📊 **Investment Crash**: Portfolio recovery time simulation
- 📈 **EMI Rate Hike**: RBI rate increase impact modeling

Each generates month-by-month balance trends and personalized AI insights.

### 5. **Expense Limit Management**
- Daily, weekly, monthly, and quarterly spending limits
- Per-category spending caps (e.g., "₹2,000/month for Food")
- Real-time monitoring via Cloud Functions
- Smart FCM notifications (80% warning, 100% alert)

### 6. **Comprehensive Dashboard & Analytics**
- 📊 **Dashboard**: Income, expense, savings, and risk score overview
- 💳 **Expense Fragment**: Category-wise spending charts (bar, donut), transaction history
- 💎 **Wealth Portfolio**: Assets (stocks, mutual funds, gold, crypto, real estate, FDs) and liabilities (home loans, car loans, credit cards) with net worth calculation
- 🎯 **Goals Management**: Set, track, and achieve financial goals with AI insights
- 🎰 **Simulations**: Save and compare multiple financial scenarios
- 📈 **Custom Charts**: Bar, line, donut, and dual-axis visualizations

---

## 🏗️ Technology Stack

### Frontend (Android)
- **Language**: Kotlin
- **Architecture**: MVVM (Model-View-ViewModel) with LiveData
- **UI Framework**: 
  - Material Design 3
  - Navigation Component (NavGraph-based)
  - BottomNavigationView, DrawerLayout
  - Custom chart views (Bar, Line, Donut, Dual-axis)
- **PDF Processing**: PDFBox Android
- **OCR**: ML Kit Text Recognition for scanned PDFs
- **Async Tasks**: Kotlin Coroutines, WorkManager
- **SMS Parsing**: Android BroadcastReceiver with deterministic ID factory
- **Local Storage**: SharedPreferences for merchant cache and expense limits
- **Target SDK**: Android 36 | Min SDK: 24

### Backend (Firebase)
- **Authentication**: Firebase Auth (OTP-based phone login)
- **Database**: Firestore (NoSQL, real-time)
  - Collections for transactions, goals, assets, liabilities, merchant categories, expense limits, and simulation records
- **Cloud Functions**: Firebase Functions v1 (asia-south1 region)
  - Real-time transaction aggregation
  - Spending limit checks
  - Automatic FCM alert generation
- **Messaging**: Firebase Cloud Messaging (FCM) for push notifications
- **Google Services**: Firebase BOM 34.11.0

### Build System
- **Gradle** with Kotlin DSL
- **JVM Target**: Java 11

---

## 📋 Project Structure

```
Arthsetu/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/team_arthsetu/
│   │   │   ├── MainActivity.kt (Navigation hub)
│   │   │   ├── model/ (Data classes)
│   │   │   ├── engine/ (Business logic - Risk, Insights, Scenarios)
│   │   │   ├── repository/ (Firestore access)
│   │   │   ├── receiver/ (SMS parsing, FCM handling)
│   │   │   ├── bankstatement/ (PDF parsing & OCR)
│   │   │   ├── ui/ (Fragments & Activities)
│   │   │   ├── viewmodel/ (MVVM ViewModels)
│   │   │   ├── utils/ (Helpers & utilities)
│   │   │   └── fcm/ (Firebase Cloud Messaging)
│   │   └── res/ (Layouts, drawables, animations, colors, menus, navigation)
│   ├── build.gradle.kts
│   └── google-services.json
├── Cloud_Functions/
│   ├── src/
│   │   └── index.ts (Firebase Cloud Functions)
│   ├── functions/
│   └── package.json
├── gradle/ (Gradle wrapper & libs versions)
└── README.md

```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio (latest version)
- Android SDK 24+
- Node.js 16+ (for Firebase Cloud Functions)
- Firebase Project with:
  - Firebase Auth enabled
  - Firestore database configured
  - Cloud Functions deployed
  - Firebase Cloud Messaging enabled

### Installation & Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/Siddhu-Dhok/Siddhu-Dhok-Hackathon-Codesphere26-Arthsetu.git
   cd Arthsetu
   ```

2. **Setup Firebase Project**
   - Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
   - Download `google-services.json` and place it in `app/` directory
   - Configure Firestore security rules

3. **Deploy Cloud Functions**
   ```bash
   cd Cloud_Functions
   npm install
   firebase deploy --only functions
   ```

4. **Open in Android Studio**
   - File → Open → Select Arthsetu folder
   - Gradle will auto-sync dependencies
   - Run on emulator or physical device

5. **Grant Required Permissions**
   - READ_SMS (for bank SMS parsing)
   - RECEIVE_SMS (for SMS interception)
   - POST_NOTIFICATIONS (for FCM alerts)
   - INTERNET & ACCESS_NETWORK_STATE (for Firebase communication)

---

## 📱 Key Workflows

### Transaction Capture & Categorization Flow
```
Bank SMS → SMS Receiver → Regex Parsing → Amount/Merchant Extraction
                                          ↓
                         Deduplication Check (5-layer)
                                          ↓
                         Categorization (4-tier)
                                          ↓
                         Firestore Insert → Cloud Function Trigger
                                          ↓
                         Aggregation (Daily/Weekly/Monthly/Quarterly)
                                          ↓
                         Limit Checks → FCM Notifications
```

### Scenario Simulation Flow
```
User Input (Initial Balance, Income, Expenses, Assets, Liabilities)
                              ↓
                    Scenario Parameter Selection
                              ↓
                    Scenario Engine Processing
                              ↓
          Month-by-Month Balance Simulation
                              ↓
                    Risk Assessment & Insights
                              ↓
                  Save to Firestore & Display Results
```

---

## 🔐 Security & Privacy

- ✅ OTP-based phone authentication
- ✅ Firebase Security Rules for data access control
- ✅ SMS data processed locally when possible
- ✅ Secure transaction ID hashing (SHA-256)
- ✅ Cloud Functions validate all data modifications

---

## 🎓 What You'll Learn

This project demonstrates:
- **Android Development**: MVVM architecture, Kotlin Coroutines, Material Design 3
- **Firebase Integration**: Firestore real-time sync, Cloud Functions, FCM, Firebase Auth
- **Financial Algorithms**: Risk scoring, scenario simulation, budget optimization
- **Data Processing**: SMS parsing with regex, PDF extraction with OCR
- **Real-time Systems**: Push notifications, reactive data streams
- **Duplicate Prevention**: Multi-layer deduplication strategies
- **Backend Development**: TypeScript Cloud Functions, database design

---

## 📊 Supported Indian Banks

Arthsetu supports automatic SMS parsing from 50+ Indian banks including:
- State Bank of India (SBI)
- HDFC Bank
- ICICI Bank
- Axis Bank
- Kotak Mahindra Bank
- Yes Bank
- IDFC Bank
- *and many more...*

---

## 🤝 Team

### Author
**Siddhesh Dhok**  
🔗 GitHub: [@Siddhu-Dhok](https://github.com/Siddhu-Dhok)

### Contributors
- **Ajinkya Sultane**
- **Nishant Tarone**

---

## 📄 Project Documentation

For more details about the project, features, and development process, please refer to:  
📊 [Codesphere Team ArthSetu.pptx](./Codesphere%20Team%20ArthSetu.pptx)

---

## 📝 License

This project was developed for the **Codesphere26 Hackathon**.

---

## 🌟 Future Enhancements

- 🤖 Machine Learning-based expense categorization
- 📊 Income stability tracking and seasonal analysis
- 🚨 Spending anomaly detection (Z-score outliers)
- 💡 AI-powered budget recommendations
- 🔄 Direct bank API integration for seamless data sync
- 📱 iOS version
- 💬 AI chatbot for financial queries

---

**Made with ❤️ for the Codesphere26 Hackathon**

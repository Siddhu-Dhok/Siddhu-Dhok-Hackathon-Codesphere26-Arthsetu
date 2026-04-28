# Arthsetu Project - Comprehensive Analysis & Flow

## Executive Summary

**Arthsetu** is a personal financial management platform that:

- Captures transactions via SMS parsing, bank statement PDF import, and manual entry
- Categorizes transactions using a 4-tier fallback mechanism
- Evaluates financial risk using expense ratios and savings metrics
- Enforces spending limits (daily/weekly/monthly/quarterly + per-category)
- Triggers FCM notifications for limit breaches and uncategorized merchants
- Provides financial scenarios and wealth management features

**Tech Stack**: Android (Kotlin/MVVM) + Firebase Firestore + Cloud Functions (1st gen, asia-south1)

---

## 1. INPUT SOURCES & TRANSACTION CAPTURE

### **1.1 SMS Transaction Capture (Real-Time)**

**Files**:

- `SmsTransactionReceiver.kt` - Broadcast receiver for SMS events
- `SmsParser.kt` - Parses bank SMS messages
- `SmsTransactionWorker.kt` - WorkManager task for async processing
- `TransactionIdFactory.kt` - Generates deterministic TX IDs

**Supported Banks** (50+): SBI, HDFC, ICICI, Axis, Kotak, Yes Bank, IDFC, Federal, Canara, IOB, Indian Bank, etc.

**SMS Parsing Flow**:

```
SMS Broadcast → SmsTransactionReceiver.onReceive()
    ↓
    Enqueue WorkManager task (SmsTransactionWorker)
    ↓
    Parse SMS body for bank patterns:
    - Amount: Regex (INR|Rs.|₹) + digits
    - Type: Keywords (debited/credited/spent/received/withdrawn/sent)
    - Merchant: Pattern matching (Info:, to/from, VPA, ATM)
    - Reference#: Bank ref, Txn#, UPI Ref (6-20 digits)
    - Date: 10+ date formats (dd-MMM-yy, ISO, etc.)
    ↓
    Generate deterministic TX ID:
    SHA-256(SMS_body | amount | timestamp | merchant)
    ↓
    Firestore.addTransactionWithIdIfAbsent() [idempotent write]
    ↓
    Cloud Function triggers:
    - Update totals (daily/weekly/monthly/quarterly)
    - Check limits → FCM alert if exceeded
    - Send category prompt if uncategorized (debit)
```

**Key Design**:

- Deterministic SHA-256 ID ensures multipart SMS retransmission idempotency
- Async WorkManager prevents blocking main thread
- Atomic Firestore write-if-absent prevents duplicates

### **1.2 Bank Statement PDF Import**

**Files**:

- `BankStatementActivity.kt` - UI for PDF selection & review
- `PdfTransactionParser.kt` - Main PDF extraction logic
- `PdfTextNormalizer.kt` - Text cleanup
- `StatementLineParser.kt` - Line-by-line parsing
- `BankStatementOcrExtractor.kt` - ML Kit OCR fallback
- `BankStatementSmsPdfMatcher.kt` - Duplicate detection

**PDF Import Flow**:

```
User selects PDF
    ↓
PDFBox text extraction (embedded text layer)
    ↓
IF sparse text (<80 meaningful chars):
    Use ML Kit OCR + OcrImagePreprocessor
    └─ Rotate/deskew, resize, threshold images
    ↓
Parse each line:
    Date | Description | Amount | Balance
    ↓
Extract merchant from description (regex patterns)
    ↓
User review UI:
    - Confirm/edit date, merchant, amount
    - Assign category (CategoryResolver)
    ↓
Batch insert (transactionId = SHA-256 of PDF row + amount + date)
    ↓
Cloud Function aggregates + notifies
```

**Deduplication Strategy** (MultiPath):

- **Primary**: Bank reference # match + amount match → same transaction
- **Fallback**: Same calendar day + amount (±₹0.02) + merchant compatible
- Returns unmatched SMS debits for user review

### **1.3 Manual Entry**

**File**: `ManualEntryBottomSheetFragment.kt`

Simple form: amount → merchant → date → category → type → Firestore insert

---

## 2. TRANSACTION COMPARISON & DUPLICATE DETECTION

### **Multi-Layer Deduplication Strategy**

| Layer                       | Mechanism                                                     | Coverage                                        | Example                                    |
| --------------------------- | ------------------------------------------------------------- | ----------------------------------------------- | ------------------------------------------ |
| **L1: Document ID**         | SHA-256(body\|amount\|ts\|merchant)                           | SMS deduplication, multipart PDU retransmission | Same SMS parsed twice by network = same ID |
| **L2: Atomic Transaction**  | `addTransactionWithIdIfAbsent()` with Firestore read→write    | Database-level conflict prevention              | Concurrent writes rejected at DB           |
| **L3: SMS-to-PDF**          | Ref# match OR (same day + amount ±₹0.02 + merchant)           | Statement import duplicate detection            | SMS "Ref#ABC123" matched with PDF stmt     |
| **L4: Merchant Normalizer** | `looseKey()` (letters+digits) OR `strictKey()` (letters only) | Variant matching                                | ZOMATO vs ZOMATODELIVERY recognized        |
| **L5: Reference Parsing**   | Extract bank ref# from SMS/statement separately               | Normalized digit-only matching                  | Ref# "TXN-2026-04-001" → "2026040001"      |

### **Implementation**

**TransactionIdFactory.kt**:

```kotlin
fun generateIdempotentId(body: String, amount: Double, ts: Long, merchant: String): String {
    val normalized = "$body|$amount|$ts|$merchant"
    return SHA256(normalized)
}
```

**Firestore Transaction**:

```kotlin
// Atomic: read user doc → check if ID exists → write if absent
firestore.runTransaction {
    val existing = tx.get(transactionRef).exists()
    if (!existing) {
        tx.set(transactionRef, txnData)
    }
}
```

**BankStatementSmsPdfMatcher.kt** (Deduplication Example):

```kotlin
fun matchSmsToStatement(sms: SmsTransaction, stmt: StatementTransaction): Boolean {
    // Primary: exact reference match
    if (sms.bankReference == stmt.bankReference) return true

    // Fallback: same day + amount tolerance + merchant comparison
    return sms.date == stmt.date &&
           Math.abs(sms.amount - stmt.amount) < 0.02 &&
           merchantsCompatible(sms.merchant, stmt.merchant)
}
```

---

## 3. CATEGORIZATION LOGIC (4-Tier Fallback)

### **Tier 1: Merchant Mapping (User-Assigned, Cached)**

**File**: `MerchantCategoryRemoteCache.kt`

- User assigns merchant → category (e.g., "ZOMATO" → "Food")
- Stored in Firestore: `users/{uid}/merchantCategories/{merchantKey}`
- Locally cached (SharedPreferences) for offline access
- Lookup on future transactions with same merchant

### **Tier 2: Cloud Merchant Service**

**File**: `MerchantCategoryRequestThrottle.kt`

- REST API call throttled to 3rd-party service (e.g., Clearbit, industry API)
- Fallback if Tier 1 misses
- Cached response

### **Tier 3: Hardcoded Keyword Rules**

**File**: `CategoryClassifier.kt`

**Debit Categories**:

- **Food**: ZOMATO, SWIGGY, DOMINO'S, McDONALD'S, KFC, RESTAURANT, CAFE, BAKERY, etc.
- **Transport**: UBER, OLA, RAPIDO, METRO, PARKING, TAXI, AUTO, etc.
- **Fuel**: PETROL, HPCL, IOCL, BPCL, FUEL, PUMP, etc.
- **Shopping**: AMAZON, FLIPKART, MYNTRA, DMART, BIGBASKET, GROCERY, MALL, etc.
- **Entertainment**: NETFLIX, SPOTIFY, HOTSTAR, PRIME, GAMING, MOVIE, etc.
- **Bills**: JIO, AIRTEL, VODAFONE, ELECTRICITY, WATER, GAS, INTERNET, etc.
- **Healthcare**: APOLLO, PHARMACY, NETMEDS, HOSPITAL, DOCTOR, CLINIC, LAB, etc.
- **Uncategorized**: (default fallback)

**Credit Categories** (Priority order):

1. **Cashback** (keyword: "cashback", "reward")
2. **Reversal/Refund** (keyword: "reversal", "refund", "return")
3. **Income** (keyword: "salary", "payment", "transfer in")
4. **Other Income** (fallback for credit)

### **Tier 4: User Categorization (Manual)**

**Files**:

- `MerchantCategoryActivity.kt` - UI for assigning category
- `CategoryRequiredNotifier.kt` - FCM prompt
- `LocalCategoryPromptDeduper.kt` - Dedupes within 15s window

**Flow**:

```
Uncategorized debit transaction detected
    ↓
Cloud Function sends CATEGORY_REQUIRED FCM
    ↓
User clicks notification → MerchantCategoryActivity
    ↓
User selects category from dropdown
    ↓
Updates Firestore:
    users/{uid}/merchantCategories/{merchantKey}: category
    ↓
Future txns with same merchant auto-categorized
```

### **CategoryResolver Orchestration**

**File**: `CategoryResolver.kt`

```kotlin
fun resolve(merchant: String, amountDebit: Double, type: String): String {
    // Tier 1: Merchant cache (fastest)
    val cachedCategory = merchantCategoryCache.get(merchant)
    if (cachedCategory != null) return cachedCategory

    // Tier 2: Cloud service (throttled, cached)
    val cloudCategory = merchantCategoryRemoteCache.fetch(merchant)
    if (cloudCategory != null) return cloudCategory

    // Tier 3: Hardcoded rules + keywords
    val rulesCategory = categoryClassifier.classify(merchant, type)
    if (rulesCategory != "Uncategorized") return rulesCategory

    // Tier 4: User will categorize + send FCM prompt
    return "Uncategorized"
}
```

---

## 4. RISK EVALUATION / SCORING ALGORITHM

### **Risk Score Formula** (0–100 scale)

$$\text{risk} = \min(50, \text{termExpense}) + \min(30, \text{termLowSavings}) + \min(20, \text{termHighSpending})$$

**File**: `RiskCalculator.kt`

### **Components**

| Term                   | Formula                                                             | Max | Meaning                                    |
| ---------------------- | ------------------------------------------------------------------- | --- | ------------------------------------------ |
| **Expense Term**       | (totalExpense / totalIncome) × 50, capped at 50                     | 50  | Higher expense ratio → higher risk         |
| **Low Savings Term**   | Binary: savingsRate < 10% ? 30 : 0                                  | 30  | Savings < 10% of income = financial stress |
| **High Spending Term** | Binary: (expense/income > 85%) OR (food > 35% of expenses) ? 20 : 0 | 20  | Unsustainable consumption patterns         |

### **Risk Levels**

- **Low**: score < 40
- **Medium**: 40 ≤ score < 70
- **High**: score ≥ 70

### **Calculation Example**

```
All-time metrics:
- Total Income: ₹1,00,000
- Total Expense: ₹80,000
- Savings: ₹20,000
- Food Expense: ₹28,000 (35% of expenses)

Calculation:
1. Expense Ratio: 80,000 / 100,000 = 0.8
   → termExpense = min(50, 0.8 × 50) = min(50, 40) = 40

2. Savings Rate: 20,000 / 100,000 = 0.2 = 20%
   → 20% > 10% → lowSavingsFactor = 0
   → termLowSavings = 0

3. High Spending Check:
   - Expense/Income = 0.8 (not > 0.85) ✓
   - Food/Expense = 28,000 / 80,000 = 0.35 (not > 0.35) ✓
   → highSpendingFactor = 0
   → termHighSpending = 0

Risk Score: 40 + 0 + 0 = 40 → MEDIUM risk
```

### **Returns from RiskCalculator.compute()**

```kotlin
Result(
    totalExpense: Double,
    totalIncome: Double,
    savings: Double,
    riskScore: Double,           // 0–100
    riskLevel: String,           // "Low", "Medium", "High"
    lowSavingsFactor: Double,    // 0 or 1
    highSpendingFactor: Double   // 0 or 1
)
```

---

## 5. NOTIFICATIONS & ALERTS

### **FCM Alert Types**

**File**: `Cloud_Functions/src/index.ts` (onTransactionCreatedAggregateAndNotify)

#### **A. Spending Limit Alerts (LIMIT_ALERT)**

- **Trigger**: Daily/Weekly/Monthly/Quarterly total ≥ respective limit
- **Data Payload**:
  ```json
  {
    "type": "LIMIT_ALERT",
    "period": "daily|weekly|monthly|quarterly",
    "total": "₹Y",
    "limit": "₹X",
    "body": "You crossed your {period} limit",
    "notification_id": "9200-9203" (stable, prevents duplicate notifications)
  }
  ```
- **Channel**: `expense_limit_alerts` (IMPORTANCE_HIGH, vibration)
- **Deduplication**: Stable notification ID = period + uid (replaces previous notification)
- **Handler**: `ArthFirebaseMessagingService.onMessageReceived()`

#### **B. Category Limit Alerts (CATEGORY_LIMIT_ALERT)**

- **Trigger**: Monthly spending in category (e.g., "Food") ≥ category-specific limit
- **Data Payload**:
  ```json
  {
    "type": "CATEGORY_LIMIT_ALERT",
    "period": "monthly",
    "category": "Food",
    "total": "₹2,500",
    "limit": "₹2,000"
  }
  ```
- **Deduplication**: category + uid

#### **C. Categorization Required Alerts (CATEGORY_REQUIRED)**

- **Trigger**: Debit transaction marked "Uncategorized"
- **Data Payload**:
  ```json
  {
    "type": "CATEGORY_REQUIRED",
    "merchant": "ZOMATO",
    "merchantLooseKey": "ZOMATO",
    "amount": "₹400",
    "transactionId": "txn_123…"
  }
  ```
- **Click Action**: Opens `MerchantCategoryActivity` to assign category
- **Deduplication**: `LocalCategoryPromptDeduper` (15s window)

#### **D. Local Offline Alerts**

**File**: `NotificationHelper.kt`

- **Trigger**: SMS worker processes offline, detects limit exceeded
- **Function**: `NotificationHelper.showLocalLimitExceededBackup()`
- **Behavior**: Shows LOCAL notification for every debit after limit hit (can be noisy)

### **FCM Handler**

**File**: `ArthFirebaseMessagingService.kt`

```kotlin
override fun onMessageReceived(remoteMessage: RemoteMessage) {
    val type = remoteMessage.data["type"] ?: return

    when (type) {
        "LIMIT_ALERT" -> showStyledLimitAlert(remoteMessage)
        "CATEGORY_LIMIT_ALERT" -> showCategoryLimitAlert(remoteMessage)
        "CATEGORY_REQUIRED" -> showCategoryPrompt(remoteMessage)
        else -> handleGenericNotification(remoteMessage)
    }
}
```

**Foreground Priority**: Styled notification with dynamic text
**Background Priority**: System shows FCM notification + `android.notification` fields

---

## 6. INCOME STABILITY CALCULATION

### **Current State**: Simplified (NOT a full predictive model)

**Key Gap**: App assumes **fixed monthly income** for baseline calculations.

### **What Exists**

**1. Income Modeling in Scenarios**

**File**: `ScenarioEngine.kt`

Scenarios use income concepts:

- **Base Income**: `monthlyIncome` (user-provided)
- **Secondary Income**: Side gigs, spouse income (scenario input)
- **Income Drop**: Explores X% reduction (e.g., "What if I lose my job?")
- **Monthly Burn Rate**: (Monthly Expenses + EMI - Monthly Income)

**2. Income-Based Insights**

**File**: `InsightsEngine.kt`

Calculates:

- Monthly surplus/deficit: `monthlyIncome - monthlyExpenses`
- Break-even analysis: "If income drops 20%, cut expenses by ₹X to match"
- EMI-to-income ratio: Warns if EMI > 30% of income

### **What's Missing**

❌ **No variance tracking** (income fluctuation over time)
❌ **No seasonality detection** (e.g., Q4 bonus, monsoon rainy days fewer expenses)
❌ **No confidence intervals** (68–95% likely income range)
❌ **No predictive modeling** (forecasting next month's income)
❌ **No gig-work volatility**

### **Future Enhancement Opportunity**

```kotlin
// Proposed Income Stability Module:
data class IncomeStabilityReport(
    val avgMonthlyIncome: Double,
    val stdDev: Double,           // ±variance
    val coefficient: Double,       // StdDev / Mean (lower = more stable)
    val minMonth: Double,
    val maxMonth: Double,
    val trend: String,            // "stable" / "decreasing" / "increasing"
    val riskLevel: String         // "Low" / "Medium" / "High"
)

fun analyzeIncomeStability(lastNMonths: List<Double>): IncomeStabilityReport {
    // Calculate mean, std dev, trend
    // Flag if gig-income data is sparse/missing
    // Return actionable insights
}
```

---

## 7. ANOMALY DETECTION & SPENDING VELOCITY

### **Current State**: Partially Implemented (Foundation Ready)

**Files Involved**:

- `RiskCalculator.kt` (basic flags)
- `Cloud_Functions/src/index.ts` (data aggregation)
- `ScenarioEngine.kt` (burn rate simulation)

### **What Exists** ✅

**1. Spending Pattern Flags** (RiskCalculator)

- **Food > 35% of expenses**: Flagged as `highSpendingFactor = 1.0`
- **Expense > 85% of income**: Flagged as unsustainable consumption
- Result: Risk level "High"

**2. Period Totals Aggregation** (Cloud Function)

Cloud Function stores:

```
users/{uid}/totals:
  {
    daily_total: 2500,
    weekly_total: 8000,
    monthly_total: 35000,
    quarterly_total: 105000,
    category_monthly: {
      "2026-04": { "Food": 2500, "Transport": 800, "Shopping": 1200 },
      "2026-03": { "Food": 2300, "Transport": 750, "Shopping": 1100 }
    }
  }
```

**Enables future anomaly detection**: Has historical data per period & category

**3. Spending Velocity (Implicit in Simulations)**

**File**: `ScenarioEngine.kt`

Monthly burn rate within scenarios:

```kotlin
val monthlySurplus = monthlyIncome - monthlyExpenses
val canAffordEmi = monthlySurplus > emiPayment
```

Used for scenario outcome (e.g., "Can you survive 6 months of 30% income drop?")

### **What's NOT Implemented** ❌

- ❌ **Z-score outlier detection**: No real-time anomaly scoring on transaction amounts
- ❌ **Merchant-specific baselines**: No historical mean/stddev per merchant
- ❌ **Real-time velocity alerts**: No alert when spending 2x usual daily rate
- ❌ **Category velocity**: No signal for "Food spend today is 3x daily average"
- ❌ **Time-series analysis**: No ARIMA/exponential smoothing for trend detection
- ❌ **ML-based outlier scoring**: No model to predict "normal" vs "anomaly"

### **Opportunity** (For Phase 2 Development)

```typescript
// Proposed Cloud Function enhancement:

interface MerchantStats {
  merchantKey: string;
  countAllTime: number;
  avgAmount: number;
  stdDev: number;
  maxAmount: number;
  minAmount: number;
  lastTransaction: number;
}

// On each transaction:
function detectAnomaly(txn: Transaction, stats: MerchantStats): AnomalyScore {
  const zScore =
    (txn.amount - stats.avgAmount) / (stats.stdDev || stats.avgAmount * 0.5);

  if (Math.abs(zScore) > 2.5) {
    // Alert: "Unusual transaction amount for {merchant}"
    // Severity = Math.min(zScore, 4.0)
  }

  return { zScore, severity, recommendation: "Review transaction" };
}
```

---

## 8. COMPLETE APPLICATION FLOW DIAGRAM

### **High-Level Architecture Diagram**

```
┌─────────────────────────────────────────────────────────────────┐
│                    ARTHSETU Application Flow                     │
└─────────────────────────────────────────────────────────────────┘

                        ┌──────────────────┐
                        │  User Onboarding │
                        │  - OTP Auth      │
                        │  - Profile Setup │
                        │  - Set Limits    │
                        └────────┬─────────┘
                                 │
                ┌────────────────┼────────────────┐
                │                │                │
         ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐
         │ SMS Capture │  │ PDF Import  │  │Manual Entry │
         │ (Real-Time) │  │  (Batch)    │  │   (UI)      │
         └──────┬──────┘  └──────┬──────┘  └──────┬──────┘
                │                │                │
                └────────────────┼────────────────┘
                                 │
                      ┌──────────▼────────────┐
                      │  Transaction Parser   │
                      │ - Amount extraction   │
                      │ - Merchant normalize  │
                      │ - Date parsing        │
                      │ - TX ID generation    │
                      └──────────┬────────────┘
                                 │
                      ┌──────────▼────────────┐
                      │ Duplicate Detection   │
                      │ - L1: Document ID    │
                      │ - L2: Atomic TX      │
                      │ - L3: SMS-PDF match  │
                      │ - L4: Merchant norm  │
                      │ - L5: Ref# parsing   │
                      └──────────┬────────────┘
                                 │
                      ┌──────────▼────────────┐
                      │ Categorization       │
                      │ Tier 1: User cached  │
                      │ Tier 2: Cloud API    │
                      │ Tier 3: Hardcoded    │
                      │ Tier 4: User manual  │
                      └──────────┬────────────┘
                                 │
                      ┌──────────▼────────────┐
                      │ Firestore INSERT     │
                      │ (Transaction doc)    │
                      └──────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │ Cloud Function Trigger │
                    │ onTransactionCreate    │
                    └────────────┬────────────┘
                                 │
                ┌────────────────┼────────────────┐
                │                │                │
         ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐
         │ Aggregate   │  │ Check Limits│  │ Check Cat.  │
         │ Totals      │  │ & Send FCM  │  │ Limits      │
         │ (daily/wk   │  │ (LIMIT_ALERT)│ │ (CAT_LIMIT) │
         │  /mo/qtr)   │  │             │  │             │
         └─────────────┘  └─────────────┘  └─────────────┘
                                 │
                                 │ FCM Notification
                                 │ (if needed)
                                 ▼
                      ┌──────────────────┐
                      │ Android App FCM   │
                      │ Handler           │
                      │ Show Notification │
                      └──────────────────┘

      ┌────────────────────────────────────────────┐
      │   Parallel Flows: Dashboard & Analytics    │
      └────────────────────────────────────────────┘
                                 │
          ┌──────────┬───────────┼──────────┬──────────┐
          │          │           │          │          │
    ┌─────▼────┐ ┌───▼────┐ ┌───▼────┐ ┌──▼──┐ ┌─────▼────┐
    │Dashboard │ │Expense │ │ Wealth │ │Goals│ │Scenarios │
    │Reads     │ │Reads   │ │ Reads  │ │     │ │Simulate  │
    │Totals/   │ │Txns    │ │Assets+ │ │     │ │life      │
    │Risk      │ │by Cat. │ │Liab.   │ │     │ │events    │
    │Score     │ │        │ │        │ │     │ │          │
    └──────────┘ └────────┘ └────────┘ └─────┘ └──────────┘
```

### **Detailed Transaction Processing Flow**

```
SMS / PDF / Manual Input
     │
     ▼
┌─────────────────────────────────┐
│  STEP 1: Parse & Normalize      │
├─────────────────────────────────┤
│ Input: Raw SMS / PDF line / Form│
│ ↓ Extract: amount, type, date,  │
│   merchant, reference#          │
│ ↓ Normalize: uppercase merchant,│
│   UTC date, remove symbols      │
│ Output: Parsed transaction      │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 2: Generate TX ID         │
├─────────────────────────────────┤
│ ID = SHA256(                    │
│   body | amount | ts | merchant │
│ )                               │
│ (Deterministic & idempotent)    │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 3: Categorization         │
├─────────────────────────────────┤
│ ↓ Tier 1: User cache            │
│   (merchantCategories)          │
│ ↓ Tier 2: Cloud API             │
│ ↓ Tier 3: Hardcoded rules       │
│ ↓ Tier 4: Fallback to           │
│   "Uncategorized" + flag FCM    │
│ Output: category                │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 4: Dedup Check            │
├─────────────────────────────────┤
│ ↓ SMS: Check existing by ID     │
│ ↓ PDF: Match with existing SMS  │
│   via ref# or (date + amount +  │
│   merchant similarity)          │
│ ↓ If duplicate: SKIP            │
│ ↓ If unique: Proceed            │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 5: Firestore INSERT       │
├─────────────────────────────────┤
│ addTransactionWithIdIfAbsent(   │
│   {uid}/{transactionId},        │
│   {amount, type, category,      │
│    merchant, timestamp, ...}    │
│ )                               │
│ (Atomic: prevents race condition)│
└────────┬────────────────────────┘
         │ (new document created)
         ▼
┌─────────────────────────────────┐
│  STEP 6: Cloud Function Trigger │
│  onTransactionCreated...        │
├─────────────────────────────────┤
│ A. Load user doc (limits,       │
│    fcmToken, totals)            │
│ B. Compute period keys (UTC):   │
│    - day: 2026-04-01            │
│    - week: 2026-W14             │
│    - month: 2026-04             │
│    - quarter: 2026-Q2           │
│ C. Atomic TX: Update totals     │
│    (if same key, increment;     │
│     else reset & set to amount) │
│ D. Update category_monthly      │
│    [monthKey][category] +=amount│
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 7: Limit Checks & FCM     │
├─────────────────────────────────┤
│ FOR each period (daily/       │
│            weekly/monthly/quarterly):
│   IF total >= limit           │
│     → Send LIMIT_ALERT FCM    │
│            (stable notif ID)   │
│ IF isDebit AND category_cap > 0:
│   IF monthSpend >= catCap     │
│     → Send CATEGORY_LIMIT FCM │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 8: Uncategorized Prompt   │
├─────────────────────────────────┤
│ IF category == "Uncategorized"  │
│    AND type == "debit":         │
│   → Send CATEGORY_REQUIRED FCM  │
│     (dedup within 15s window)   │
└────────┬────────────────────────┘
         │ (FCM queued)
         ▼
    ┌─────────┐
    │  Complete
    └─────────┘
```

### **Risk Score Calculation Flow**

```
┌─────────────────────────────────┐
│  Load All Transactions          │
│  for user (all-time)            │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 1: Calculate Totals       │
├─────────────────────────────────┤
│ totalExpense = SUM(amount where │
│                type == "debit")  │
│ totalIncome = SUM(amount where  │
│               type == "credit") │
│ savings = totalIncome -         │
│           totalExpense          │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 2: Expense Term           │
├─────────────────────────────────┤
│ expenseRatio = MIN(              │
│   totalExpense / MAX(1,          │
│     totalIncome), 2.0)           │
│ termExpense = MIN(50,            │
│   expenseRatio × 50)             │
│                                 │
│ Example:                        │
│  Expense: ₹80K, Income: ₹100K  │
│  Ratio: 0.8                    │
│  → termExpense = MIN(50, 40) = 40│
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 3: Low Savings Term       │
├─────────────────────────────────┤
│ IF totalIncome <= 0:            │
│   lowSavings = (totalExpense > 0)│
│ ELSE:                           │
│   savingsRate = savings /       │
│     totalIncome                 │
│   lowSavings = (savingsRate     │
│     < 0.10)                     │
│ termLowSavings = (lowSavings ? 30 : 0)
│                                 │
│ Example:                        │
│  Savings: ₹20K, Income: ₹100K  │
│  Rate: 0.2 (20%)               │
│  → 20% > 10% → NOT low savings  │
│  → termLowSavings = 0           │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 4: High Spending Term     │
├─────────────────────────────────┤
│ foodDebit = SUM(amount where    │
│   type=="debit" AND             │
│   category=="Food")             │
│ highSpending = (               │
│   (totalExpense > 0 AND        │
│    foodDebit/totalExpense > 0.35) │
│   OR (totalIncome > 0 AND       │
│    totalExpense/totalIncome    │
│    > 0.85))                     │
│ termHighSpending = (highSpending │
│   ? 20 : 0)                     │
│                                 │
│ Example:                        │
│  Food: ₹28K, Expense: ₹80K    │
│  Ratio: 0.35 (NOT > 0.35) ✓    │
│  Exp/Inc: 0.8 (< 0.85) ✓      │
│  → highSpending = false         │
│  → termHighSpending = 0         │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 5: Aggregate Risk Score   │
├─────────────────────────────────┤
│ riskScore = MIN(100,            │
│   termExpense +                 │
│   termLowSavings +              │
│   termHighSpending)             │
│                                 │
│ Example:                        │
│  40 + 0 + 0 = 40                │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  STEP 6: Determine Level        │
├─────────────────────────────────┤
│ IF riskScore < 40:              │
│   riskLevel = "Low"             │
│ ELSE IF score < 70:             │
│   riskLevel = "Medium"          │
│ ELSE:                           │
│   riskLevel = "High"            │
│                                 │
│ Example:                        │
│  40 → score >= 40 && < 70       │
│  → riskLevel = "Medium"         │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  Return RiskCalculator.Result   │
├─────────────────────────────────┤
│ {                               │
│   totalExpense,                 │
│   totalIncome,                  │
│   savings,                      │
│   riskScore,                    │
│   riskLevel,                    │
│   lowSavingsFactor,             │
│   highSpendingFactor            │
│ }                               │
└─────────────────────────────────┘
```

---

## 9. IDENTIFIED BUGS & ISSUES

### **Critical Bugs**

#### **BUG #1: Risk Score Calculation - Division by Zero Edge Case**

**Severity**: MEDIUM  
**File**: `RiskCalculator.kt` (line ~40)

**Issue**:

```kotlin
val incomeForRatio = totalIncome.coerceAtLeast(1.0)  // Prevents DIV/0
val expenseRatio = (totalExpense / incomeForRatio).coerceIn(0.0, 2.0)
```

**Problem**: If `totalIncome == 0` and `totalExpense > 0`:

- `incomeForRatio` becomes 1.0 (forced)
- `expenseRatio` = `totalExpense / 1.0` = inflated (e.g., ₹80K becomes 80.0 if income is zero)
- Risk score over-inflated incorrectly

**Impact**: User with no recorded income but some expenses gets artificially high risk score

**Fix**:

```kotlin
// Better handling:
val riskScore = when {
    totalIncome <= 0 && totalExpense > 0 -> 100.0  // No income = critical risk
    totalIncome <= 0 && totalExpense <= 0 -> 0.0   // No activity
    else -> compute normally
}
```

---

#### **BUG #2: SMS Deduplication - Timestamp Precision Mismatch**

**Severity**: MEDIUM  
**Files**: `SmsParser.kt`, `TransactionIdFactory.kt`

**Issue**:
SMS parsing extracts date but Cloud Function uses precise timestamp (milliseconds). If two SMSs from same transaction arrive with slightly different timestamps:

```kotlin
// SmsParser extracts date with possible rounding:
val dateFromSms = parseDate("04-APR-2026") // Day only, no time

// TransactionIdFactory creates ID:
val id = SHA256("body|5000|1743825600000|ZOMATO")  // ts in ms
```

If same SMS is parsed at `14:05:23 IST` and `14:05:25 IST`:

- Different timestamps → different TX IDs (despite atomic write preventing insert)
- SMS parsing might create two TX docs if timing is unfortunate

**Impact**: Database inconsistency or duplicate transactions in edge cases

**Fix**:

```kotlin
// Round timestamp to day-level granularity for SMS:
val dayBucket = (timestampMs / (24 * 60 * 60 * 1000)) * (24 * 60 * 60 * 1000)
val id = SHA256("body|amount|$dayBucket|merchant")
```

---

#### **BUG #3: Cloud Function - Missing Category in High Spending Check**

**Severity**: LOW  
**File**: `Cloud_Functions/src/index.ts` (line ~280)

**Issue**:

```typescript
const foodDebit = transactions
    .filter { it.type == Constants.DEBIT && it.category.equals("Food", ignoreCase=true) }
    .sumOf { it.amount }
```

But Cloud Function **does NOT have access to full transaction history** when evaluating the `highSpending` flag:

```typescript
const amountForTotals = isDebit && Number.isFinite(txnAmount) ? txnAmount : 0;
// Later: checking high spending but only current transaction data
if (isDebit && catCap > 0 && totalsResult.category_month_spend >= catCap) {
  // ✓ This is correct (uses stored monthly total)
}
```

The Cloud Function can only check **current month's category total** (from `category_monthly`), NOT all-time ratio used by RiskCalculator.

**Impact**: Risk score (computed on Android with all-time data) and Cloud Function alerts (computed on monthly data) may diverge. User might be flagged "High Risk" but Food alerts come later.

**Fix**:
Ensure both Android and Cloud Function use consistent time windows, or pass risk indicators in transaction metadata.

---

#### **BUG #4: Merchant Normalization Inconsistency**

**Severity**: MEDIUM  
**Files**: `MerchantNormalizer.kt`, `Cloud_Functions/src/index.ts`

**Issue**:
Android has `looseKey()` (letters+digits) and `strictKey()` (letters only). Cloud Function uses:

```typescript
const merchantLooseKey = String(txnMerchant ?? "")
  .trim()
  .toUpperCase()
  .replace(/[^A-Z0-9]/g, ""); // ✓ Same as Android looseKey
```

But category lookup uses:

```kotlin
// Android CategoryResolver:
fun resolve(merchant: String, ...): String {
    val merchantKey = MerchantNormalizer.looseKey(merchant)
    return cache[merchantKey]  // or cloud API, etc.
}
```

If Android caches under `"ZOMATODELIVERY"` but incoming SMS just says `"ZOMATO"`, the Cloud Function sends different `merchantLooseKey` → user sees duplicate categorization prompts.

**Impact**: Merchant categorization cache misses, redundant FCM category prompts

**Fix**:
Sync both normalization logic (ideally shared TypeScript module or Firestore rules).

---

#### **BUG #5: Local Notification - Spam Risk**

**Severity**: MEDIUM  
**File**: `NotificationHelper.kt`

**Issue**:

```kotlin
fun showLocalLimitExceededBackup() {
    // Called on EVERY debit after limit hit (no deduplication)
    notificationManager.notify(
        NOTIFICATION_ID_LIMIT_ALERT,
        builder.build()
    )
}
```

If limit is ₹5,000/day and user spends ₹10K:

- 1st txn (₹3,500): Triggers notification
- 2nd txn (₹2,000): Triggers ANOTHER notification (same NOTIFICATION_ID, so replaces)
- 3rd txn (₹4,500): ANOTHER notification

**Impact**: User sees notification **for every single transaction** after limit, even if limit was already exceeded. Extremely noisy.

**Fix**:

```kotlin
fun showLocalLimitExceededBackup() {
    // Only show if not already shown today
    val lastShownKey = "limit_alert_${period}_${today}"
    if (sharedPrefs.getBoolean(lastShownKey, false)) {
        return  // Already showed today
    }

    notificationManager.notify(NOTIFICATION_ID, builder.build())
    sharedPrefs.putBoolean(lastShownKey, true)
}
```

---

#### **BUG #6: Category Monthly Totals - Credit Transactions Included**

**Severity**: LOW  
**File**: `Cloud_Functions/src/index.ts` (line ~250)

**Issue**:

```typescript
const monthBucket = { ...monthBucketPrev };
const catKey = txnCategory.trim() || "Uncategorized";
const newCatSpend = prevCatSpend + amountForTotals; // ← ALL txns (credit + debit)
monthBucket[catKey] = newCatSpend;
```

Later:

```typescript
const catCap = limitForCategory(categoryLimitsMap, totalsResult.category_key);
if (isDebit && catCap > 0 && totalsResult.category_month_spend >= catCap) {
  // Send alert
}
```

**The condition only checks `isDebit`**, so if a credit comes in, `amountForTotals = 0`. But design intent is unclear: Should category limits include credits?

**Impact**: Minor — works because `amountForTotals = 0` for credits anyway. But could be confusing.

**Fix**:

```typescript
const amountForTotals = isDebit ? amount : 0; // Explicit debit-only
```

---

#### **BUG #7: Cloud Function FCM Token Validation Too Permissive**

**Severity**: LOW  
**File**: `Cloud_Functions/src/index.ts` (line ~320)

**Issue**:

```typescript
if (typeof fcmToken !== "string" || fcmToken.length === 0) {
  console.log("STEP4 ROOT CAUSE: fcmToken missing...");
  return;
}
```

Then later:

```typescript
async function sendFcmDataOnlyHighPriority(args: {
  token: string;
  data: Record<string, string>;
}): Promise<string | null> {
  const message = {
    token: args.token, // ← Could be malformed
    // ...
  };
  await admin.messaging().send(message);
}
```

If `fcmToken` is present but **malformed** (e.g., too short, wrong format), Firebase will return an error and continue without retrying.

**Impact**: Lost notifications silently; user unaware they're not receiving alerts

**Fix**:

```typescript
function isValidFcmToken(token: unknown): boolean {
  if (typeof token !== "string") return false;
  // FCM tokens are typically 152+ chars, alphanumeric
  return token.length > 100 && /^[a-zA-Z0-9_-]+$/.test(token);
}

if (!isValidFcmToken(fcmToken)) {
  logger.error("Invalid FCM token format", { uid, token: maskToken(fcmToken) });
  return;
}
```

---

#### **BUG #8: Week Number Calculation Edge Case**

**Severity**: LOW  
**File**: `Cloud_Functions/src/index.ts` (line ~68–76)

**Issue**:

```typescript
function utcWeekKey(tsMs: number): string {
  const date = new Date(tsMs);
  date.setUTCHours(0, 0, 0, 0);
  const day = (date.getUTCDay() + 6) % 7; // Mon=0..Sun=6
  date.setUTCDate(date.getUTCDate() - day + 3);
  const firstThursday = new Date(Date.UTC(date.getUTCFullYear(), 0, 4));
  const firstDay = (firstThursday.getUTCDay() + 6) % 7;
  firstThursday.setUTCDate(firstThursday.getUTCDate() - firstDay + 3);
  const week =
    1 + Math.round((date.getTime() - firstThursday.getTime()) / 604800000);
  return `${date.getUTCFullYear()}-W${String(week).padStart(2, "0")}`;
}
```

The ISO week number calculation uses `Math.round()` which can cause rounding to W0 or W54 on year boundaries.

**Example Edge Case**:

- Date: Dec 31, 2026 (might round to Week 1 of next year)
- Week aggregation might jump incorrectly

**Impact**: Week total resets unexpectedly near year boundaries

**Fix**:

```typescript
const week =
  Math.floor((date.getTime() - firstThursday.getTime()) / 604800000) + 1;
```

---

### **Design Issues (Not Bugs)**

#### **DESIGN ISSUE #1: No Multi-Currency Support**

**File**: Multiple transaction handling files

**Issue**: All calculations assume INR. No currency field in transaction model.

**Impact**: App won't work for users with multi-currency accounts or international transfers.

#### **DESIGN ISSUE #2: Incomplete Anomaly Detection**

**Details**: Covered in Section 7.

**Impact**: App can't detect unusual transaction amounts or spending spikes in real-time.

#### **DESIGN ISSUE #3: Weak Income Stability Model**

**Details**: Covered in Section 6.

**Impact**: Risk score doesn't account for income volatility (gig workers, seasonal income).

#### **DESIGN ISSUE #4: No Transaction Reversal Handling**

**Issue**: If a transaction is reversed (refund), it's treated as a separate credit. App doesn't link them.

**Impact**: Dashboard shows both debit and credit; merchant totals are inflated.

---

## 10. SUMMARY TABLE: Bug Tracker

| ID  | Title                                 | Severity | File(s)                                | Fix Effort | Status                  |
| --- | ------------------------------------- | -------- | -------------------------------------- | ---------- | ----------------------- |
| #1  | Risk Score - DIV/0 Edge               | MEDIUM   | RiskCalculator.kt                      | LOW        | TODO                    |
| #2  | SMS Dedup - Timestamp Mismatch        | MEDIUM   | SmsParser.kt + TransactionIdFactory.kt | MEDIUM     | TODO                    |
| #3  | Cloud Func - Missing Category History | LOW      | index.ts                               | HIGH       | Limitation              |
| #4  | Merchant Norm Inconsistency           | MEDIUM   | MerchantNormalizer.kt + index.ts       | MEDIUM     | TODO                    |
| #5  | Local Notification Spam               | MEDIUM   | NotificationHelper.kt                  | LOW        | TODO                    |
| #6  | Category Totals - Credit Ambiguity    | LOW      | index.ts                               | LOW        | Won't Fix (works as-is) |
| #7  | FCM Token Validation                  | LOW      | index.ts                               | LOW        | TODO                    |
| #8  | Week Number Edge Case                 | LOW      | index.ts                               | LOW        | TODO                    |

---

## 11. RECOMMENDATIONS

### **Immediate Priorities** (Next Sprint)

1. **Fix Bug #5** (Local Notification Spam) - Easy, high impact
2. **Fix Bug #1** (Risk Score Edge Case) - Medium effort, affects user experience
3. **Fix Bug #2** (SMS Dedup Timestamp) - Prevents data corruption

### **Medium-Term** (Next Quarter)

4. Add formal anomaly detection module (Section 7)
5. Implement income stability tracking (Section 6)
6. Implement transaction reversal linking

### **Long-Term** (Roadmap)

7. Multi-currency support
8. ML-based merchant categorization (instead of keywords)
9. Predictive spending forecasts
10. API integration for bank statement auto-sync (instead of manual PDF)

---

## 12. CODEBASE STRUCTURE SUMMARY

```
arthsetu/
├── app/src/main/java/com/example/team_arthsetu/
│   ├── utils/
│   │   ├── RiskCalculator.kt           [Risk scoring]
│   │   ├── TransactionIdFactory.kt     [TX ID generation]
│   │   ├── MerchantNormalizer.kt       [Merchant key]
│   │   └── ...
│   ├── sms/
│   │   ├── SmsTransactionReceiver.kt   [Broadcast receiver]
│   │   ├── SmsParser.kt                [SMS parsing]
│   │   ├── SmsTransactionWorker.kt     [WorkManager task]
│   │   └── ...
│   ├── bankstatement/
│   │   ├── BankStatementActivity.kt    [UI]
│   │   ├── PdfTransactionParser.kt     [PDF parsing]
│   │   ├── BankStatementSmsPdfMatcher.kt [Dedup]
│   │   └── ...
│   ├── categorization/
│   │   ├── CategoryResolver.kt         [4-tier orchestration]
│   │   ├── CategoryClassifier.kt       [Hardcoded rules]
│   │   ├── MerchantCategoryRemoteCache.kt [Cache]
│   │   └── ...
│   ├── repository/
│   │   ├── FirestoreRepository.kt      [Data layer]
│   │   └── ...
│   ├── viewmodel/
│   │   ├── DashboardViewModel.kt       [Risk + totals]
│   │   ├── ExpenseViewModel.kt         [Transaction list]
│   │   ├── SimulationViewModel.kt      [Scenarios]
│   │   └── ...
│   ├── ui/
│   │   ├── DashboardFragment.kt
│   │   ├── ExpenseFragment.kt
│   │   ├── MerchantCategoryActivity.kt [User categorization]
│   │   └── ...
│   ├── fcm/
│   │   ├── ArthFirebaseMessagingService.kt [FCM handler]
│   │   └── ...
│   ├── engine/
│   │   ├── ScenarioEngine.kt           [Financial simulations]
│   │   └── InsightsEngine.kt           [Insights generation]
│   └── ...
│
├── Cloud_Functions/
│   ├── src/index.ts                    [Cloud Function]
│   │   └── onTransactionCreatedAggregateAndNotify
│   └── package.json
│
└── gradle/, README.md, etc.
```

---

**End of Analysis**

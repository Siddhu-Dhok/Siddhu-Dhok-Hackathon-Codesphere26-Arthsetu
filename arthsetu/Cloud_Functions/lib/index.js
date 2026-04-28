"use strict";
/**
 * Arthsetu Cloud Functions (1st gen — avoids Eventarc / v2 IAM issues on new projects)
 *
 * Triggers (default Firestore DB, asia-south1):
 * - users/{uid}/transactions/{transactionId}:
 *   - update users/{uid}.totals.* (event-driven aggregation)
 *   - compare against users/{uid}.limits.* and send FCM if exceeded
 *   - if uncategorized, send FCM prompting categorization
 */
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.onTransactionCreatedAggregateAndNotify = void 0;
const admin = __importStar(require("firebase-admin"));
const functions = __importStar(require("firebase-functions"));
const logger = __importStar(require("firebase-functions/logger"));
admin.initializeApp();
const FUNCTION_REGION = "asia-south1";
const USERS = "users";
const TRANSACTIONS = "transactions";
const FIELD_FCM_TOKEN = "fcmToken";
/** Matches Android [Constants.DEBIT] */
const TX_DEBIT = "debit";
// Clean schema (preferred):
// users/{uid}.limits.* and users/{uid}.totals.*
const LIMITS = "limits";
const TOTALS = "totals";
const FIELD_DAILY_LIMIT = "daily_limit";
const FIELD_WEEKLY_LIMIT = "weekly_limit";
const FIELD_MONTHLY_LIMIT = "monthly_limit";
const FIELD_QUARTERLY_LIMIT = "quarterly_limit";
const FIELD_DAILY_TOTAL = "daily_total";
const FIELD_WEEKLY_TOTAL = "weekly_total";
const FIELD_MONTHLY_TOTAL = "monthly_total";
const FIELD_QUARTERLY_TOTAL = "quarterly_total";
const FIELD_DAILY_KEY = "daily_key";
const FIELD_WEEKLY_KEY = "weekly_key";
const FIELD_MONTHLY_KEY = "monthly_key";
const FIELD_QUARTERLY_KEY = "quarterly_key";
// Backward compatibility (existing app fields):
const FIELD_EXPENSE_LIMIT_DAILY = "expenseLimitDaily";
const FIELD_EXPENSE_LIMIT_WEEKLY = "expenseLimitWeekly";
const FIELD_EXPENSE_LIMIT_MONTHLY = "expenseLimitMonthly";
const FIELD_EXPENSE_LIMIT_QUARTERLY = "expenseLimitQuarterly";
/** users/{uid}.categoryLimits map → monthly cap per category name */
const FIELD_CATEGORY_LIMITS_DOC = "categoryLimits";
/** Inside users/{uid}.totals — monthKey → { categoryName: spent } (UTC month, debit only) */
const FIELD_CATEGORY_MONTHLY_TOTALS = "category_monthly";
function asNumberOrZero(v) {
    return typeof v === "number" && !Number.isNaN(v) ? v : 0;
}
function utcDayKey(tsMs) {
    const d = new Date(tsMs);
    const y = d.getUTCFullYear();
    const m = String(d.getUTCMonth() + 1).padStart(2, "0");
    const day = String(d.getUTCDate()).padStart(2, "0");
    return `${y}-${m}-${day}`;
}
function utcMonthKey(tsMs) {
    const d = new Date(tsMs);
    const y = d.getUTCFullYear();
    const m = String(d.getUTCMonth() + 1).padStart(2, "0");
    return `${y}-${m}`;
}
function utcQuarterKey(tsMs) {
    const d = new Date(tsMs);
    const y = d.getUTCFullYear();
    const q = Math.floor(d.getUTCMonth() / 3) + 1;
    return `${y}-Q${q}`;
}
function utcWeekKey(tsMs) {
    // ISO week (UTC)
    const date = new Date(tsMs);
    date.setUTCHours(0, 0, 0, 0);
    const day = (date.getUTCDay() + 6) % 7; // Mon=0..Sun=6
    date.setUTCDate(date.getUTCDate() - day + 3);
    const firstThursday = new Date(Date.UTC(date.getUTCFullYear(), 0, 4));
    const firstDay = (firstThursday.getUTCDay() + 6) % 7;
    firstThursday.setUTCDate(firstThursday.getUTCDate() - firstDay + 3);
    const week = 1 + Math.round((date.getTime() - firstThursday.getTime()) / 604800000);
    return `${date.getUTCFullYear()}-W${String(week).padStart(2, "0")}`;
}
function getLimitFromUserDoc(userSnap, key) {
    const nested = userSnap.get(`${LIMITS}.${key}`);
    if (typeof nested === "number" && !Number.isNaN(nested))
        return nested;
    // legacy fallbacks (flat fields written by Android)
    if (key === FIELD_DAILY_LIMIT)
        return asNumberOrZero(userSnap.get(FIELD_EXPENSE_LIMIT_DAILY));
    if (key === FIELD_WEEKLY_LIMIT)
        return asNumberOrZero(userSnap.get(FIELD_EXPENSE_LIMIT_WEEKLY));
    if (key === FIELD_MONTHLY_LIMIT)
        return asNumberOrZero(userSnap.get(FIELD_EXPENSE_LIMIT_MONTHLY));
    if (key === FIELD_QUARTERLY_LIMIT)
        return asNumberOrZero(userSnap.get(FIELD_EXPENSE_LIMIT_QUARTERLY));
    return 0;
}
function getCategoryLimitsMap(userSnap) {
    const raw = userSnap.get(FIELD_CATEGORY_LIMITS_DOC);
    if (!raw || typeof raw !== "object")
        return {};
    const out = {};
    for (const [k, v] of Object.entries(raw)) {
        const n = typeof v === "number" ? v : Number(v);
        if (Number.isFinite(n) && n > 0)
            out[k] = n;
    }
    return out;
}
/** Match Android category name with Firestore map keys (case-insensitive fallback). */
function limitForCategory(limits, categoryName) {
    const trimmed = categoryName.trim();
    if (trimmed.length > 0 && limits[trimmed] > 0)
        return limits[trimmed];
    const lower = trimmed.toLowerCase();
    for (const [k, v] of Object.entries(limits)) {
        if (k.toLowerCase() === lower && v > 0)
            return v;
    }
    return 0;
}
/** Notify when spend crosses this fraction of the cap (separate from full LIMIT_ALERT at 100%). */
const LIMIT_WARNING_THRESHOLD = 0.8;
function stableNotificationId(base, salt) {
    let h = 0;
    for (let i = 0; i < salt.length; i++)
        h = (h * 31 + salt.charCodeAt(i)) | 0;
    return base + (Math.abs(h) % 200);
}
function maskToken(token) {
    if (typeof token !== "string" || token.length === 0)
        return String(token);
    return `${token.slice(0, 12)}…len=${token.length}`;
}
/**
 * Sends a high-priority **data-only** FCM message so Android always delivers to
 * [ArthFirebaseMessagingService.onMessageReceived] (foreground and background), and the
 * client posts notifications on the correct channels with full formatting and actions.
 * Including `android.notification` would let the system show a tray notification without
 * invoking the service, which skipped LIMIT_ALERT / merchant-category handling.
 */
async function sendFcmDataOnlyHighPriority(args) {
    const message = {
        token: args.token,
        data: args.data,
        android: {
            priority: "high",
        },
    };
    try {
        const response = await admin.messaging().send(message);
        console.log("FCM RESPONSE (data-only high priority):", response);
        return response;
    }
    catch (error) {
        console.error("FCM ERROR:", error);
        return null;
    }
}
exports.onTransactionCreatedAggregateAndNotify = functions
    .region(FUNCTION_REGION)
    .runWith({
    memory: "256MB",
    timeoutSeconds: 120,
})
    .firestore.document(`${USERS}/{uid}/${TRANSACTIONS}/{transactionId}`)
    .onCreate(async (snap, context) => {
    const uid = context.params.uid;
    const transactionId = context.params.transactionId;
    console.log("STEP1 triggered", { uid, transactionId });
    const txnAmountRaw = snap.get("amount");
    const txnTypeRaw = snap.get("type");
    const txnTsRaw = snap.get("timestamp");
    const txnCategoryRaw = snap.get("category");
    const txnMerchantRaw = snap.get("merchant");
    console.log("DEBUG created transaction fields", {
        amount: txnAmountRaw,
        type: txnTypeRaw,
        timestamp: txnTsRaw,
        category: txnCategoryRaw,
        merchant: txnMerchantRaw,
    });
    const db = admin.firestore();
    const userRef = db.collection(USERS).doc(uid);
    const userSnap = await userRef.get();
    if (!userSnap.exists) {
        console.log("STEP2: FAIL — users/{uid} document missing");
        return;
    }
    const fcmToken = userSnap.get(FIELD_FCM_TOKEN);
    const tokenStr = maskToken(fcmToken);
    const dailyLimit = getLimitFromUserDoc(userSnap, FIELD_DAILY_LIMIT);
    const weeklyLimit = getLimitFromUserDoc(userSnap, FIELD_WEEKLY_LIMIT);
    const monthlyLimit = getLimitFromUserDoc(userSnap, FIELD_MONTHLY_LIMIT);
    const quarterlyLimit = getLimitFromUserDoc(userSnap, FIELD_QUARTERLY_LIMIT);
    const categoryLimitsMap = getCategoryLimitsMap(userSnap);
    console.log("DEBUG user doc snapshot", {
        fcmToken: tokenStr,
        limits: { dailyLimit, weeklyLimit, monthlyLimit, quarterlyLimit },
        categoryLimitKeys: Object.keys(categoryLimitsMap),
    });
    const txnAmount = typeof txnAmountRaw === "number" ? txnAmountRaw : Number(txnAmountRaw);
    const txnType = String(txnTypeRaw ?? "").toLowerCase();
    const txnTs = typeof txnTsRaw === "number" ? txnTsRaw : Number(txnTsRaw);
    const txnCategory = String(txnCategoryRaw ?? "").trim();
    const txnMerchant = String(txnMerchantRaw ?? "").trim();
    const isDebit = txnType === TX_DEBIT;
    const amountForTotals = isDebit && Number.isFinite(txnAmount) ? txnAmount : 0;
    const dayKey = utcDayKey(txnTs);
    const weekKey = utcWeekKey(txnTs);
    const monthKey = utcMonthKey(txnTs);
    const quarterKey = utcQuarterKey(txnTs);
    // STEP3: event-driven totals update (no full-history reads)
    const totalsResult = await db.runTransaction(async (tx) => {
        const u = await tx.get(userRef);
        const data = u.exists ? (u.data() ?? {}) : {};
        const totals = data[TOTALS] ?? {};
        const prevDayKey = String(totals[FIELD_DAILY_KEY] ?? "");
        const prevWeekKey = String(totals[FIELD_WEEKLY_KEY] ?? "");
        const prevMonthKey = String(totals[FIELD_MONTHLY_KEY] ?? "");
        const prevQuarterKey = String(totals[FIELD_QUARTERLY_KEY] ?? "");
        const prevDayTotal = prevDayKey === dayKey ? asNumberOrZero(totals[FIELD_DAILY_TOTAL]) : 0;
        const prevWeekTotal = prevWeekKey === weekKey ? asNumberOrZero(totals[FIELD_WEEKLY_TOTAL]) : 0;
        const prevMonthTotal = prevMonthKey === monthKey ? asNumberOrZero(totals[FIELD_MONTHLY_TOTAL]) : 0;
        const prevQuarterTotal = prevQuarterKey === quarterKey ? asNumberOrZero(totals[FIELD_QUARTERLY_TOTAL]) : 0;
        const newDayTotal = prevDayTotal + amountForTotals;
        const newWeekTotal = prevWeekTotal + amountForTotals;
        const newMonthTotal = prevMonthTotal + amountForTotals;
        const newQuarterTotal = prevQuarterTotal + amountForTotals;
        const categoryMonthlyAll = totals[FIELD_CATEGORY_MONTHLY_TOTALS] || {};
        const monthBucketPrev = categoryMonthlyAll[monthKey] || {};
        const monthBucket = { ...monthBucketPrev };
        const catKey = txnCategory.trim() || "Uncategorized";
        const prevCatSpend = monthBucket[catKey] || 0;
        const newCatSpend = prevCatSpend + amountForTotals;
        monthBucket[catKey] = newCatSpend;
        const newCategoryMonthlyAll = { ...categoryMonthlyAll, [monthKey]: monthBucket };
        tx.set(userRef, {
            [TOTALS]: {
                [FIELD_DAILY_KEY]: dayKey,
                [FIELD_WEEKLY_KEY]: weekKey,
                [FIELD_MONTHLY_KEY]: monthKey,
                [FIELD_QUARTERLY_KEY]: quarterKey,
                [FIELD_DAILY_TOTAL]: newDayTotal,
                [FIELD_WEEKLY_TOTAL]: newWeekTotal,
                [FIELD_MONTHLY_TOTAL]: newMonthTotal,
                [FIELD_QUARTERLY_TOTAL]: newQuarterTotal,
                [FIELD_CATEGORY_MONTHLY_TOTALS]: newCategoryMonthlyAll,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            },
        }, { merge: true });
        return {
            dayKey,
            weekKey,
            monthKey,
            quarterKey,
            daily_total: newDayTotal,
            weekly_total: newWeekTotal,
            monthly_total: newMonthTotal,
            quarterly_total: newQuarterTotal,
            category_month_spend: newCatSpend,
            category_key: catKey,
        };
    });
    console.log("STEP2 totals:", totalsResult);
    // STEP4: limit check + FCM (one data message per exceeded scope so weekly/monthly/etc. all surface)
    if (typeof fcmToken !== "string" || fcmToken.length === 0) {
        console.log("STEP4 ROOT CAUSE: fcmToken missing or empty — cannot send FCM (users/{uid}.fcmToken)");
        return;
    }
    const periodAlerts = [];
    if (dailyLimit > 0 && totalsResult.daily_total >= dailyLimit) {
        periodAlerts.push({
            period: "daily",
            limit: dailyLimit,
            total: totalsResult.daily_total,
            nid: stableNotificationId(9200, `daily-${uid}`),
        });
    }
    if (weeklyLimit > 0 && totalsResult.weekly_total >= weeklyLimit) {
        periodAlerts.push({
            period: "weekly",
            limit: weeklyLimit,
            total: totalsResult.weekly_total,
            nid: stableNotificationId(9201, `weekly-${uid}`),
        });
    }
    if (monthlyLimit > 0 && totalsResult.monthly_total >= monthlyLimit) {
        periodAlerts.push({
            period: "monthly",
            limit: monthlyLimit,
            total: totalsResult.monthly_total,
            nid: stableNotificationId(9202, `monthly-${uid}`),
        });
    }
    if (quarterlyLimit > 0 && totalsResult.quarterly_total >= quarterlyLimit) {
        periodAlerts.push({
            period: "quarterly",
            limit: quarterlyLimit,
            total: totalsResult.quarterly_total,
            nid: stableNotificationId(9203, `quarterly-${uid}`),
        });
    }
    const periodWarnings80 = [];
    const prevDailyTotal = totalsResult.daily_total - amountForTotals;
    const prevWeeklyTotal = totalsResult.weekly_total - amountForTotals;
    const prevMonthlyTotal = totalsResult.monthly_total - amountForTotals;
    const prevQuarterlyTotal = totalsResult.quarterly_total - amountForTotals;
    function push80IfCrossed(period, limit, total, prevTotal, nidSalt) {
        if (limit <= 0)
            return;
        const threshold = limit * LIMIT_WARNING_THRESHOLD;
        if (prevTotal >= threshold)
            return;
        if (total < threshold)
            return;
        if (total >= limit)
            return;
        periodWarnings80.push({
            period,
            limit,
            total,
            nid: stableNotificationId(9310, nidSalt),
        });
    }
    push80IfCrossed("daily", dailyLimit, totalsResult.daily_total, prevDailyTotal, `80-daily-${uid}-${totalsResult.dayKey}`);
    push80IfCrossed("weekly", weeklyLimit, totalsResult.weekly_total, prevWeeklyTotal, `80-weekly-${uid}-${totalsResult.weekKey}`);
    push80IfCrossed("monthly", monthlyLimit, totalsResult.monthly_total, prevMonthlyTotal, `80-monthly-${uid}-${totalsResult.monthKey}`);
    push80IfCrossed("quarterly", quarterlyLimit, totalsResult.quarterly_total, prevQuarterlyTotal, `80-quarterly-${uid}-${totalsResult.quarterKey}`);
    const basePayload = {
        uid,
        transactionId,
        amount: String(txnAmountRaw ?? ""),
        txnType,
        daily_total: String(totalsResult.daily_total),
        weekly_total: String(totalsResult.weekly_total),
        monthly_total: String(totalsResult.monthly_total),
        quarterly_total: String(totalsResult.quarterly_total),
        daily_limit: String(dailyLimit),
        weekly_limit: String(weeklyLimit),
        monthly_limit: String(monthlyLimit),
        quarterly_limit: String(quarterlyLimit),
    };
    for (const a of periodAlerts) {
        const title = "Limit Exceeded";
        const body = `You crossed your ${a.period} limit`;
        const dataPayload = {
            type: "LIMIT_ALERT",
            ...basePayload,
            total: String(Math.round(a.total)),
            limit: String(Math.round(a.limit)),
            period: a.period,
            title,
            body,
            notification_id: String(a.nid),
        };
        console.log("STEP4 sending LIMIT_ALERT FCM period=", a.period, "to:", maskToken(fcmToken));
        const messageId = await sendFcmDataOnlyHighPriority({ token: fcmToken, data: dataPayload });
        if (messageId) {
            logger.info("Limit FCM sent", { uid, transactionId, period: a.period, messageId });
        }
    }
    for (const w of periodWarnings80) {
        const pct = Math.round(LIMIT_WARNING_THRESHOLD * 100);
        const title = "Approaching expense limit";
        const body = `You've used ${pct}% or more of your ${w.period} limit`;
        const warnPayload = {
            type: "LIMIT_WARNING_80",
            ...basePayload,
            total: String(Math.round(w.total)),
            limit: String(Math.round(w.limit)),
            period: w.period,
            threshold_percent: String(pct),
            title,
            body,
            notification_id: String(w.nid),
        };
        console.log("STEP4 sending LIMIT_WARNING_80 FCM period=", w.period, "to:", maskToken(fcmToken));
        const warnId = await sendFcmDataOnlyHighPriority({ token: fcmToken, data: warnPayload });
        if (warnId) {
            logger.info("80% limit warning FCM sent", { uid, transactionId, period: w.period, messageId: warnId });
        }
    }
    const catCap = limitForCategory(categoryLimitsMap, totalsResult.category_key);
    if (isDebit && catCap > 0 && totalsResult.category_month_spend >= catCap) {
        const catNid = stableNotificationId(9400, `${totalsResult.category_key}-${uid}`);
        const catPayload = {
            type: "CATEGORY_LIMIT_ALERT",
            ...basePayload,
            category: totalsResult.category_key,
            limit: String(Math.round(catCap)),
            total: String(Math.round(totalsResult.category_month_spend)),
            period: "monthly",
            title: `${totalsResult.category_key} limit exceeded`,
            body: `Monthly ${totalsResult.category_key} spend crossed your cap`,
            notification_id: String(catNid),
        };
        console.log("STEP4 sending CATEGORY_LIMIT_ALERT FCM category=", totalsResult.category_key);
        const messageId = await sendFcmDataOnlyHighPriority({ token: fcmToken, data: catPayload });
        if (messageId) {
            logger.info("Category limit FCM sent", { uid, transactionId, messageId });
        }
    }
    if (periodAlerts.length === 0 &&
        periodWarnings80.length === 0 &&
        !(isDebit && catCap > 0 && totalsResult.category_month_spend >= catCap)) {
        console.log("STEP4 No limit/category/warning FCM — totals within configured caps");
    }
    // STEP5: uncategorized merchant prompt — debit only (no FCM for credits)
    const catLower = txnCategory.trim().toLowerCase();
    const isUncategorized = catLower === "uncategorized" || catLower === "un-categorized" || catLower === "";
    if (isUncategorized && isDebit) {
        console.log("STEP5 Uncategorized debit merchant — sending CATEGORY_REQUIRED FCM");
        console.log("STEP4 sending FCM to:", maskToken(fcmToken));
        const merchantLooseKey = String(txnMerchant ?? "")
            .trim()
            .toUpperCase()
            .replace(/[^A-Z0-9]/g, "");
        const messageId = await sendFcmDataOnlyHighPriority({
            token: fcmToken,
            data: {
                type: "CATEGORY_REQUIRED",
                transactionId,
                txnId: transactionId, // backward compatibility
                merchant: txnMerchant,
                merchantLooseKey,
                notifySource: "server",
                amount: String(txnAmountRaw ?? ""),
                txnType,
                title: "New merchant",
                body: `Categorize new merchant: ${txnMerchant}`,
            },
        });
        if (messageId) {
            logger.info("Uncategorized merchant FCM sent", { uid, transactionId, messageId });
        }
    }
});
//# sourceMappingURL=index.js.map
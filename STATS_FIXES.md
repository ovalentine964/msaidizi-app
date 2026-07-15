# Applied Statistics Fixes — Msaidizi App

**Date:** 2026-07-16  
**Scope:** 3 minor applied statistics corrections for statistical rigor

---

## Fix 1: Population Variance → Sample Variance (Bessel's Correction)

**File:** `app/src/main/java/com/msaidizi/app/agent/proactive/ProactiveAnomalyDetector.kt`  
**Method:** `calculateStats()`

### Problem
Used `.average()` which divides by `n` (population variance), producing a biased underestimate of variance when working with samples.

### Fix
Changed to Bessel's correction (n−1 denominator) for an unbiased sample variance estimate:

```kotlin
// Before (biased):
val variance = values.map { (it - mean) * (it - mean) }.average()

// After (unbiased):
val n = values.size
val variance = values.map { (it - mean) * (it - mean) }.sum() / (n - 1).coerceAtLeast(1)
```

**Rationale:** When estimating population variance from a sample, dividing by `n−1` corrects for the downward bias introduced by using the sample mean (which minimizes the sum of squared deviations). The `.coerceAtLeast(1)` prevents division by zero when n=1.

---

## Fix 2: z-score Approximation → t-distribution for A/B Test p-values

**File:** `app/src/main/java/com/msaidizi/app/agent/LearningAgent.kt`  
**Method:** `analyzeABTestResults()`

### Problem
Used fixed z-score thresholds (1.645, 1.96, 2.576, 3.29) regardless of degrees of freedom. For small samples, this is anti-conservative — it underestimates p-values, making results appear more significant than they are.

### Fix
Added a `tCriticalValues(df)` lookup function that returns the correct two-tailed critical values from the t-distribution based on `df = n1 + n2 - 2`:

```kotlin
// Before (z-score, ignores sample size):
val z = abs(tStat)
val pValue = when {
    z > 3.29 -> 0.001
    z > 2.576 -> 0.01
    z > 1.96 -> 0.05
    z > 1.645 -> 0.10
    else -> 0.5
}

// After (t-distribution, df-aware):
val df = n1 + n2 - 2
val tCrit = tCriticalValues(df)
val absT = abs(tStat)
val pValue = when {
    absT > tCrit.p001 -> 0.001
    absT > tCrit.p01 -> 0.01
    absT > tCrit.p05 -> 0.05
    absT > tCrit.p10 -> 0.10
    else -> 0.5
}
```

**Rationale:** The t-distribution has heavier tails than the normal distribution, especially for small df. For example, at df=5, the 95% critical value is 2.571 (vs. 1.96 for z). This means small-sample A/B tests require stronger evidence to declare significance — the correct, conservative behavior. For large df (≥120), t-values converge to z-values, so large-sample behavior is unchanged.

| df   | t₀.₀₅  | z      | Difference |
|------|---------|--------|------------|
| 5    | 2.571   | 1.960  | +31%       |
| 10   | 2.228   | 1.960  | +14%       |
| 30   | 2.042   | 1.960  | +4%        |
| 120+ | 1.960   | 1.960  | ≈0%        |

---

## Fix 3: Normality Check with CLT Guidance

**File:** `app/src/main/java/com/msaidizi/app/agent/AnalysisAgent.kt`  
**Method:** `getDescriptiveStatistics()` + `SalesDescriptiveStats` data class

### Problem
Descriptive statistics reported skewness and kurtosis but didn't flag when normality assumptions might be violated for small samples, or note when the Central Limit Theorem (CLT) justifies normal-based inference.

### Fix
Added a `normalityNote` field to `SalesDescriptiveStats` that provides context-aware guidance:

```kotlin
val normalityNote = when {
    n >= 30 -> "CLT applies (n≥30): sample mean is approximately normal"
    skewness > 1.0 || skewness < -1.0 ->
        "WARNING: Highly skewed data (γ₁=%.2f) with small n=%d. " +
        "Consider non-parametric methods (e.g., median, IQR)"
    kurtosis > 2.0 || kurtosis < -2.0 ->
        "WARNING: Heavy-tailed data (γ₂=%.2f) with small n=%d. " +
        "Normal-based inference may be unreliable"
    else -> "Small sample (n=%d): normality assumed but unverified"
}
```

**Rationale:** 
- For `n ≥ 30`, the CLT guarantees that the sampling distribution of the mean is approximately normal, regardless of the underlying population distribution. This is explicitly noted.
- For `n < 30` with high skewness (|γ₁| > 1) or excess kurtosis (|γ₂| > 2), the normal approximation may be poor. Users are warned to consider non-parametric alternatives (median, IQR, rank-based tests).
- The `SalesDescriptiveStats` data class gained a `normalityNote: String` field with a default empty string for backward compatibility.

---

## Summary

| # | Issue | File | Change |
|---|-------|------|--------|
| 1 | Population variance (n) | ProactiveAnomalyDetector.kt | → Sample variance (n−1) |
| 2 | z-score p-values | LearningAgent.kt | → t-distribution critical values |
| 3 | Missing normality check | AnalysisAgent.kt | Added normality assessment + CLT note |

All fixes are minimal, backward-compatible, and follow existing code style.

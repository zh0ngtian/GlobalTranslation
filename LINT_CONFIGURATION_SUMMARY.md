> 上游历史文档，保留用于追溯；不代表当前拍照翻译版本。当前规则与实现见仓库根目录 `AGENTS.md` 和 `README.md`。

# Lint Configuration Summary

## Overview
This document summarizes the additional lint checks that have been enabled for the GlobalTranslation project.

## Changes Made

### 1. Dependency Updates
- **AGP (Android Gradle Plugin)**: Updated from `8.12.0` to `8.13.1`
- **Kotlin**: Kept at `2.2.20` (2.2.21 not yet compatible with KSP)

### 2. Lint Configuration Files

#### `app/lint.xml`
Created a comprehensive lint configuration file with the following enabled checks:

**High Priority Checks:**
- `OldTargetApi` (warning): Ensures targetSdk is up-to-date
- `StopShip` (error): Catches incomplete code before release
- `LogConditional` (warning): Optimizes logging for release builds
- `UnknownNullness` (warning): Improves Kotlin interoperability
- `NoHardKeywords` (warning): Better Kotlin interop
- `SyntheticAccessor` (informational): Reduces method count
- `KotlinPropertyAccess` (warning): Follows Kotlin conventions
- `ExpensiveAssertion` (warning): Avoids performance issues

**Medium Priority Checks:**
- `AppCompatMethod` (warning): Use correct AppCompat methods
- `SelectableText` (informational): Improves UX for data display
- `DuplicateStrings` (informational): Reduces app size
- `NegativeMargin` (warning): Catches layout issues
- `ThreadConstraint` (error): Ensures thread safety
- `LambdaLast` (warning): Better lambda parameter naming
- `Registered` (warning): Detects unregistered activities/services
- `TypographyQuotes` (informational): Typography improvements
- `ConvertToWebp` (informational): Better image compression
- `ComposableLambdaParameterNaming` (warning): Composable naming conventions
- `ComposableLambdaParameterPosition` (warning): Composable parameter position
- `RequiredSize` (error): Layout requirements
- `UnsupportedChromeOsHardware` (warning): ChromeOS support
- `NoOp` (warning): No-op code detection
- `EasterEgg` (warning): Hidden code detection
- `PermissionNamingConvention` (warning): Permission naming
- `MangledCRLF` (warning): Line ending detection
- `MemberExtensionConflict` (warning): Member/extension conflicts

#### `app/build.gradle.kts`
Enhanced lint configuration in the build file:
- Enabled `checkDependencies = true`
- Enabled `checkReleaseBuilds = true`
- Added lint baseline support for incremental improvements
- Configured `checkAllWarnings = true` to enable all library checks
- Explicitly enabled 14 high-priority checks
- Set appropriate severity levels (error, warning, informational)
- Generated HTML and XML reports

### 3. Lint Baseline
Created `app/lint-baseline.xml` to establish a baseline of existing issues (97 warnings). This allows:
- Breaking the build on newly introduced errors
- Fixing issues incrementally without blocking development
- Tracking progress as baseline issues are resolved

### 4. Code Fixes
- Fixed `ObsoleteSdkInt` issue in `NetworkMonitor.kt:35` by removing unnecessary SDK version check (minSdk is already 29)

## Benefits

### Code Quality
- **Kotlin Interoperability**: Better nullness annotations and naming conventions
- **Thread Safety**: Explicit thread constraint checking
- **Performance**: Detection of expensive assertions and unnecessary operations

### Maintainability
- **Code Style**: Consistent naming conventions for Composables
- **Best Practices**: Enforced use of AppCompat methods and proper component registration

### App Size & Performance
- **Optimization**: Detection of duplicate strings, synthetic accessors
- **Resource Management**: WebP conversion suggestions for images

### Developer Experience
- **Early Detection**: Catches issues before they reach production
- **Incremental Improvement**: Baseline allows fixing issues gradually
- **Better Documentation**: UnknownNullness checks encourage proper annotations

## Additional Checks Included (99)

The following 99 additional checks are now active from AndroidX libraries:
- Jetpack Compose checks (40+)
- Navigation checks
- Fragment lifecycle checks
- Lifecycle awareness checks
- Material Design checks
- AppCompat checks
- Dagger/Hilt checks

See the lint reports for full details.

## Running Lint

```bash
# Run lint on debug variant
./gradlew lintDebug

# Run lint on all variants
./gradlew lint

# View reports
open app/build/reports/lint-results-debug.html
```

## Next Steps

1. **Review Baseline**: Gradually fix the 97 warnings in `lint-baseline.xml`
2. **Monitor New Issues**: All new issues will fail the build (with `abortOnError = false` for now)
3. **Enable More Checks**: Consider enabling additional disabled checks as the codebase matures
4. **Update Kotlin**: Upgrade to 2.2.21 once KSP compatibility is available

## References

- [Android Lint Documentation](https://developer.android.com/studio/write/lint)
- [Jetpack Compose Lint Checks](https://issuetracker.google.com/issues/new?component=612128)
- [Kotlin Interop Guidelines](https://android.github.io/kotlin-guides/interop.html)

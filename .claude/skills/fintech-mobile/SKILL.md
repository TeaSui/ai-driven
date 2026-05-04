---
name: fintech-mobile
description: |
  Fintech mobile patterns for Flutter (primary), iOS, and Android.
  Biometric authentication flows, secure transaction handling, push notifications
  for financial alerts, KYC document capture, offline-first for financial data.
  Use when: implementing mobile banking features, biometric auth, transaction
  flows, push notifications for financial events, KYC document upload, or
  secure data display on mobile.
  Triggers on: biometric, mobile payment, transaction screen, push notification,
  KYC mobile, mobile banking, secure storage, PIN entry.
---

# Fintech Mobile Patterns

Primary platform: Flutter/Dart. iOS (Swift) and Android (Kotlin) patterns provided for native modules.

## Biometric Authentication Flow

### Flutter Implementation
```dart
// features/auth/domain/usecases/biometric_auth.dart

class BiometricAuthUseCase {
  final LocalAuthentication _localAuth;
  final SecureTokenStorage _tokenStorage;
  final AuthRepository _authRepo;

  /// Full biometric flow with fallback chain:
  /// Biometric → Device PIN/Pattern → App PIN → Password
  Future<AuthResult> authenticate() async {
    final canCheckBiometrics = await _localAuth.canCheckBiometrics;
    final isDeviceSupported = await _localAuth.isDeviceSupported();

    if (!canCheckBiometrics || !isDeviceSupported) {
      return AuthResult.fallbackRequired(FallbackType.appPin);
    }

    try {
      final authenticated = await _localAuth.authenticate(
        localizedReason: 'Verify your identity to access your account',
        options: const AuthenticationOptions(
          stickyAuth: true,       // keep auth dialog on app resume
          biometricOnly: false,   // allow device PIN as fallback
          useErrorDialogs: true,
        ),
      );

      if (!authenticated) return AuthResult.cancelled();

      // Retrieve stored refresh token after biometric success
      final refreshToken = await _tokenStorage.getRefreshToken();
      if (refreshToken == null) return AuthResult.fallbackRequired(FallbackType.password);

      // Exchange refresh token for new access token
      final tokens = await _authRepo.refreshSession(refreshToken);
      await _tokenStorage.storeTokens(tokens);
      return AuthResult.success(tokens.accessToken);

    } on PlatformException catch (e) {
      if (e.code == 'LockedOut') {
        return AuthResult.lockedOut(duration: const Duration(seconds: 30));
      }
      if (e.code == 'PermanentlyLockedOut') {
        return AuthResult.fallbackRequired(FallbackType.password);
      }
      return AuthResult.error(e.message ?? 'Biometric authentication failed');
    }
  }
}
```

### Secure Token Storage
```dart
// features/auth/data/secure_token_storage.dart

class SecureTokenStorage {
  final FlutterSecureStorage _storage;

  // iOS: Keychain with kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
  // Android: EncryptedSharedPreferences with StrongBox if available
  static const _options = IOSOptions(
    accessibility: KeychainAccessibility.passcode,
    accountName: 'com.app.auth',
  );
  static const _androidOptions = AndroidOptions(
    encryptedSharedPreferences: true,
  );

  Future<void> storeTokens(AuthTokens tokens) async {
    await _storage.write(
      key: 'access_token',
      value: tokens.accessToken,
      iOptions: _options,
      aOptions: _androidOptions,
    );
    await _storage.write(
      key: 'refresh_token',
      value: tokens.refreshToken,
      iOptions: _options,
      aOptions: _androidOptions,
    );
  }

  Future<void> clearAll() async {
    await _storage.deleteAll(iOptions: _options, aOptions: _androidOptions);
  }
}
```

### Rules
```
BIO-01: Never store biometric data — delegate to OS (LocalAuthentication/BiometricPrompt)
BIO-02: Biometric unlocks a stored credential (refresh token), not the account directly
BIO-03: Fallback chain: biometric → device PIN → app PIN → password (never skip to password)
BIO-04: Lock after 5 failed biometric attempts, require password re-authentication
BIO-05: Re-enroll biometric binding when user changes device biometrics
BIO-06: Transaction signing: require fresh biometric for payments > threshold amount
```

## Secure Transaction Display

### Transaction Screen Pattern
```dart
// features/transactions/presentation/pages/transaction_detail_page.dart

class TransactionDetailPage extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return BlocBuilder<TransactionDetailBloc, TransactionDetailState>(
      builder: (context, state) {
        return switch (state) {
          TransactionDetailLoading() => const ShimmerSkeleton(),
          TransactionDetailError(:final message) => ErrorView(message: message),
          TransactionDetailLoaded(:final transaction) => _buildDetail(transaction),
        };
      },
    );
  }

  Widget _buildDetail(Transaction tx) {
    return Scaffold(
      appBar: AppBar(title: const Text('Transaction Details')),
      body: SecureWidget(             // prevents screenshots in sensitive areas
        isSecure: true,
        child: ListView(
          children: [
            AmountDisplay(
              amount: tx.amount,      // stored in minor units (cents)
              currency: tx.currency,
              isDebit: tx.amount < 0,
            ),
            MaskedField(
              label: 'To Account',
              value: tx.recipientAccount,
              maskFn: maskAccountNumber, // ****5678
              revealable: true,
            ),
            // ... other fields
          ],
        ),
      ),
    );
  }
}
```

### Screenshot Prevention
```dart
// core/widgets/secure_widget.dart

class SecureWidget extends StatefulWidget {
  final bool isSecure;
  final Widget child;

  @override
  State<SecureWidget> createState() => _SecureWidgetState();
}

class _SecureWidgetState extends State<SecureWidget> {
  @override
  void initState() {
    super.initState();
    if (widget.isSecure) {
      // Android: FLAG_SECURE prevents screenshots
      // iOS: UITextField trick or overlay on app switcher
      FlutterWindowManager.addFlags(FlutterWindowManager.FLAG_SECURE);
    }
  }

  @override
  void dispose() {
    if (widget.isSecure) {
      FlutterWindowManager.clearFlags(FlutterWindowManager.FLAG_SECURE);
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
```

## Push Notifications for Financial Alerts

### Notification Categories
```dart
// core/notifications/notification_types.dart

enum NotificationCategory {
  transaction,        // debit/credit alerts — high priority, immediate
  security,           // login from new device, password change — critical
  kyc,               // KYC status update — normal priority
  marketing,          // offers, announcements — low priority, user opt-in
}

// Channel configuration (Android)
final channels = {
  NotificationCategory.transaction: AndroidNotificationChannel(
    'transactions',
    'Transaction Alerts',
    importance: Importance.high,
    sound: RawResourceAndroidNotificationSound('transaction_alert'),
  ),
  NotificationCategory.security: AndroidNotificationChannel(
    'security',
    'Security Alerts',
    importance: Importance.max, // heads-up notification
  ),
};
```

### FCM Handler Pattern
```dart
// core/notifications/notification_handler.dart

class NotificationHandler {
  Future<void> initialize() async {
    // Request permission (iOS)
    final settings = await FirebaseMessaging.instance.requestPermission(
      alert: true,
      badge: true,
      sound: true,
      criticalAlert: true,  // for security alerts (requires Apple entitlement)
    );

    // Handle foreground messages
    FirebaseMessaging.onMessage.listen(_handleForeground);

    // Handle background/terminated tap
    FirebaseMessaging.onMessageOpenedApp.listen(_handleNotificationTap);

    // Register device token with backend
    final token = await FirebaseMessaging.instance.getToken();
    if (token != null) await _authRepo.registerDeviceToken(token);

    // Handle token refresh
    FirebaseMessaging.instance.onTokenRefresh.listen(
      (token) => _authRepo.registerDeviceToken(token),
    );
  }

  void _handleForeground(RemoteMessage message) {
    final category = NotificationCategory.values.byName(
      message.data['category'] ?? 'transaction',
    );

    // Transaction alerts: show in-app banner (not system notification)
    if (category == NotificationCategory.transaction) {
      _showInAppBanner(message);
      // Invalidate cached balance/transactions
      getIt<TransactionBloc>().add(const RefreshTransactions());
    }

    // Security alerts: always show system notification
    if (category == NotificationCategory.security) {
      _showSystemNotification(message, priority: Priority.max);
    }
  }
}
```

### Rules
```
PUSH-01: Transaction notifications must NOT contain full account numbers or amounts in payload
PUSH-02: Notification payload: use data-only messages, build display text client-side
PUSH-03: Security alerts (new device login) must use critical/max priority
PUSH-04: User must be able to disable marketing notifications independently
PUSH-05: Invalidate cached financial data when transaction notification received
PUSH-06: Test notification delivery with both app foreground and background states
```

## KYC Document Capture

```dart
// features/kyc/presentation/widgets/document_capture.dart

class DocumentCapture extends StatelessWidget {
  final KycDocumentType documentType;
  final void Function(File) onCaptured;

  Future<void> _captureDocument(BuildContext context) async {
    final picker = ImagePicker();

    // Camera preferred for identity documents (prevents submission of screenshots)
    final source = documentType.requiresCamera
        ? ImageSource.camera
        : await _showSourcePicker(context);

    final image = await picker.pickImage(
      source: source,
      maxWidth: 2048,           // limit resolution
      maxHeight: 2048,
      imageQuality: 85,         // compress to reduce upload size
    );

    if (image == null) return;

    final file = File(image.path);
    final sizeInMb = await file.length() / (1024 * 1024);

    if (sizeInMb > 10) {
      _showError(context, 'Document must be under 10MB');
      return;
    }

    // Validate it looks like a document (basic checks)
    if (!await _isValidImage(file)) {
      _showError(context, 'Invalid document image');
      return;
    }

    onCaptured(file);
  }
}

// Document types with capture rules
enum KycDocumentType {
  nationalId(requiresCamera: true, sides: 2),
  passport(requiresCamera: true, sides: 1),
  proofOfAddress(requiresCamera: false, sides: 1),
  selfie(requiresCamera: true, sides: 1);

  final bool requiresCamera;
  final int sides;
  const KycDocumentType({required this.requiresCamera, required this.sides});
}
```

### Rules
```
KYC-MOB-01: National ID and passport: camera only (prevents screenshot/photocopy fraud)
KYC-MOB-02: Selfie: liveness detection recommended (blink/head turn prompt)
KYC-MOB-03: Upload via pre-signed S3 URL (never through app server)
KYC-MOB-04: Delete local document files immediately after successful upload
KYC-MOB-05: Show upload progress with retry on failure (resume if possible)
KYC-MOB-06: Document preview before submit — allow retake
```

## Offline-First for Financial Data

```dart
// features/transactions/data/repositories/transaction_repository_impl.dart

class TransactionRepositoryImpl implements TransactionRepository {
  final TransactionRemoteDataSource _remote;
  final TransactionLocalDataSource _local;   // Isar or Hive
  final ConnectivityService _connectivity;

  @override
  Stream<List<Transaction>> watchTransactions(String accountId) {
    // Always serve from local first
    _syncIfConnected(accountId);
    return _local.watchTransactions(accountId);
  }

  Future<void> _syncIfConnected(String accountId) async {
    if (!await _connectivity.isConnected) return;

    try {
      final lastSync = await _local.getLastSyncTimestamp(accountId);
      final remote = await _remote.getTransactions(
        accountId,
        since: lastSync,
      );
      await _local.upsertTransactions(remote);
      await _local.setLastSyncTimestamp(accountId, DateTime.now());
    } catch (e) {
      // Sync failure is non-fatal — stale data is better than no data
      debugPrint('Sync failed: $e');
    }
  }
}
```

### Offline Rules
```
OFFLINE-01: Display cached data immediately, sync in background
OFFLINE-02: Show "Last updated X minutes ago" indicator when offline
OFFLINE-03: Read-only when offline — disable transfer/payment buttons
OFFLINE-04: Queue non-financial actions offline (profile updates), sync on reconnect
OFFLINE-05: Never queue financial transactions offline — require connectivity
OFFLINE-06: Encrypted local database (Isar encryption or SQLCipher)
```

## App Security Hardening

```dart
// core/security/app_security.dart

class AppSecurity {
  /// Call on app start
  Future<void> initialize() async {
    // Root/jailbreak detection
    final isCompromised = await SafeDevice.isJailBroken ||
                          await SafeDevice.isRealDevice == false;
    if (isCompromised) {
      // Allow but warn + log (blocking breaks legitimate dev testing)
      _analyticsService.logSecurityEvent('compromised_device');
    }

    // Certificate pinning
    HttpOverrides.global = CertificatePinningOverrides(
      pins: AppConfig.certificatePins,
    );

    // App lifecycle: auto-lock after background > 5 minutes
    AppLifecycleListener(
      onStateChange: _handleLifecycleChange,
    );
  }

  void _handleLifecycleChange(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      _backgroundTimestamp = DateTime.now();
    }
    if (state == AppLifecycleState.resumed) {
      final elapsed = DateTime.now().difference(_backgroundTimestamp!);
      if (elapsed > const Duration(minutes: 5)) {
        // Require re-authentication (biometric)
        _authBloc.add(const RequireReauthentication());
      }
    }
  }
}
```

## Amount Handling

```dart
// CRITICAL: Financial amounts in minor units (cents/pips)
// Never use double for money

/// Amount stored as int (minor units). 1234 = $12.34
class Money {
  final int minorUnits;
  final String currency;

  const Money(this.minorUnits, this.currency);

  String format() {
    final formatter = NumberFormat.currency(
      locale: 'en_SG',
      symbol: currencySymbol,
      decimalDigits: decimalPlaces,
    );
    return formatter.format(minorUnits / pow(10, decimalPlaces));
  }

  int get decimalPlaces => switch (currency) {
    'SGD' || 'USD' || 'MYR' => 2,
    'JPY' => 0,
    _ => 2,
  };
}
```

## Testing Priorities

Test biometric fallback chains, offline data display with stale indicators, KYC document capture validation, and amount formatting with minor units. Use `mockLocalAuth.canCheckBiometrics` for biometric tests, mock `ConnectivityService` for offline tests.

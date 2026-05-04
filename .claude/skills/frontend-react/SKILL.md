---
name: frontend-react
description: |
  React/Next.js frontend patterns for fintech applications. Component architecture,
  state management, form handling, KYC flows, PII masking, secure data display,
  and testing patterns with Vitest + Testing Library.
  Use when: building React/Next.js frontends, KYC forms, transaction UIs,
  financial dashboards, or any frontend consuming Spring Boot/Go APIs.
  Triggers on: React, Next.js, frontend, component, form, KYC UI, dashboard,
  PII masking, transaction display.
---

# React / Next.js Patterns (Fintech)

## Assumed Stack
- **Framework:** Next.js 14+ (App Router) or React 18+ SPA
- **State:** TanStack Query (server state) + Zustand (client state)
- **Forms:** React Hook Form + Zod validation
- **Styling:** Tailwind CSS (or CSS Modules)
- **Testing:** Vitest + Testing Library
- **HTTP:** fetch (Next.js) or Axios with interceptors

If project uses a different framework (Vue, Angular), adapt patterns — the fintech domain patterns still apply.

## Project Structure (Next.js App Router)

```
src/
├── app/                          # Next.js App Router pages
│   ├── (auth)/                   # Auth-required layout group
│   │   ├── dashboard/
│   │   ├── transactions/
│   │   └── kyc/
│   ├── (public)/                 # Public layout group
│   │   ├── login/
│   │   └── register/
│   └── layout.tsx
├── components/
│   ├── ui/                       # Primitive UI components (Button, Input, Card)
│   ├── forms/                    # Form components (KycForm, TransferForm)
│   ├── data-display/             # Tables, charts, account cards
│   └── layout/                   # Header, Sidebar, Navigation
├── hooks/                        # Custom hooks
├── lib/
│   ├── api/                      # API client, interceptors, types
│   ├── auth/                     # Auth context, token management
│   ├── validation/               # Zod schemas (shared with forms)
│   └── utils/                    # Formatters, masking, helpers
├── stores/                       # Zustand stores
└── types/                        # Shared TypeScript types
```

## State Management

### Server State (TanStack Query)
```typescript
// hooks/useTransactions.ts
export function useTransactions(accountId: string, page: number) {
  return useQuery({
    queryKey: ['transactions', accountId, page],
    queryFn: () => api.getTransactions(accountId, page),
    staleTime: 30_000,        // 30s — transactions update frequently
    placeholderData: keepPreviousData, // smooth pagination
  });
}

// Mutation with optimistic update
export function useTransfer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: api.createTransfer,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['transactions'] });
      queryClient.invalidateQueries({ queryKey: ['balance'] });
    },
  });
}
```

### Client State (Zustand) — only for UI state
```typescript
// stores/useAppStore.ts
interface AppState {
  sidebarOpen: boolean;
  toggleSidebar: () => void;
}

export const useAppStore = create<AppState>((set) => ({
  sidebarOpen: true,
  toggleSidebar: () => set((s) => ({ sidebarOpen: !s.sidebarOpen })),
}));
```

**Rule:** Server data in TanStack Query, UI-only state in Zustand. Never duplicate server state in Zustand.

## Form Handling (React Hook Form + Zod)

### Validation Schema Pattern
```typescript
// lib/validation/transfer.ts
import { z } from 'zod';

export const transferSchema = z.object({
  recipientAccount: z.string()
    .min(1, 'Account number required')
    .regex(/^\d{10,16}$/, 'Invalid account number'),
  amount: z.number()
    .positive('Amount must be positive')
    .multipleOf(0.01, 'Max 2 decimal places')
    .max(50000, 'Exceeds single transfer limit'),
  currency: z.enum(['SGD', 'USD', 'MYR']),
  reference: z.string().max(140).optional(),
});

export type TransferInput = z.infer<typeof transferSchema>;
```

### Form Component Pattern
```typescript
// components/forms/TransferForm.tsx
export function TransferForm({ onSuccess }: { onSuccess: () => void }) {
  const form = useForm<TransferInput>({
    resolver: zodResolver(transferSchema),
    defaultValues: { currency: 'SGD' },
  });
  const transfer = useTransfer();

  const onSubmit = form.handleSubmit(async (data) => {
    try {
      await transfer.mutateAsync(data);
      onSuccess();
    } catch (error) {
      if (isApiError(error) && error.code === 'INSUFFICIENT_FUNDS') {
        form.setError('amount', { message: 'Insufficient funds' });
      }
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate>
      <FormField
        label="Recipient Account"
        error={form.formState.errors.recipientAccount?.message}
        {...form.register('recipientAccount')}
      />
      {/* ... other fields ... */}
      <Button type="submit" loading={transfer.isPending}>
        Transfer
      </Button>
    </form>
  );
}
```

## PII Masking Utilities

```typescript
// lib/utils/masking.ts

/** Mask account number: ****5678 */
export function maskAccount(account: string): string {
  return account.replace(/.(?=.{4})/g, '*');
}

/** Mask email: us***@domain.com */
export function maskEmail(email: string): string {
  const [local, domain] = email.split('@');
  return `${local.slice(0, 2)}***@${domain}`;
}

/** Mask phone: +65****1234 */
export function maskPhone(phone: string): string {
  return phone.replace(/(\+\d{2})\d+(\d{4})$/, '$1****$2');
}

/** Mask NRIC/FIN: ****567A */
export function maskNric(nric: string): string {
  return '****' + nric.slice(-4);
}
```

### Masked Display Component
```typescript
// components/ui/MaskedText.tsx
interface MaskedTextProps {
  value: string;
  maskFn: (v: string) => string;
  revealable?: boolean;      // allow user to toggle visibility
}

export function MaskedText({ value, maskFn, revealable = false }: MaskedTextProps) {
  const [revealed, setRevealed] = useState(false);

  return (
    <span className="inline-flex items-center gap-1">
      <span aria-label="masked value">{revealed ? value : maskFn(value)}</span>
      {revealable && (
        <button
          type="button"
          onClick={() => setRevealed(!revealed)}
          aria-label={revealed ? 'Hide' : 'Reveal'}
          className="text-xs text-blue-600 hover:underline"
        >
          {revealed ? 'Hide' : 'Show'}
        </button>
      )}
    </span>
  );
}
```

**Rule:** PII is masked by default. Reveal requires explicit user action. Log reveal events for audit.

## KYC Flow Pattern

```typescript
// app/(auth)/kyc/page.tsx — multi-step KYC wizard
const KYC_STEPS = ['personal', 'identity', 'document', 'review'] as const;

export default function KycPage() {
  const [step, setStep] = useState<number>(0);
  const [data, setData] = useState<Partial<KycData>>({});
  const kycStatus = useKycStatus(); // TanStack Query

  if (kycStatus.data?.status === 'APPROVED') {
    return <KycApproved />;
  }

  const StepComponent = {
    personal: PersonalInfoStep,
    identity: IdentityVerificationStep,
    document: DocumentUploadStep,
    review: ReviewStep,
  }[KYC_STEPS[step]];

  return (
    <KycLayout currentStep={step} totalSteps={KYC_STEPS.length}>
      <StepComponent
        data={data}
        onNext={(stepData) => {
          setData(prev => ({ ...prev, ...stepData }));
          setStep(s => s + 1);
        }}
        onBack={() => setStep(s => s - 1)}
      />
    </KycLayout>
  );
}
```

### Document Upload (Direct to S3)
```typescript
// components/forms/DocumentUpload.tsx
async function uploadDocument(file: File, type: KycDocumentType) {
  // 1. Get pre-signed URL from backend
  const { uploadUrl, documentId } = await api.getDocumentUploadUrl(type, file.type);

  // 2. Upload directly to S3 (no PII through our servers)
  await fetch(uploadUrl, {
    method: 'PUT',
    body: file,
    headers: { 'Content-Type': file.type },
  });

  // 3. Notify backend that upload is complete
  await api.confirmDocumentUpload(documentId);
  return documentId;
}
```

**Rules:**
- Max file size: 10MB (validate client-side before upload)
- Accepted types: JPEG, PNG, PDF only (validate MIME type)
- No OCR client-side — server handles extraction
- Clear file from memory after upload (URL.revokeObjectURL)

## Financial Data Display

### Currency Formatting
```typescript
// lib/utils/currency.ts
const formatters: Record<string, Intl.NumberFormat> = {};

export function formatCurrency(amount: number, currency: string = 'SGD'): string {
  if (!formatters[currency]) {
    formatters[currency] = new Intl.NumberFormat('en-SG', {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
    });
  }
  // amount from API is in minor units (cents)
  return formatters[currency].format(amount / 100);
}
```

### Transaction List
```typescript
// components/data-display/TransactionList.tsx
function TransactionRow({ tx }: { tx: Transaction }) {
  return (
    <tr>
      <td>{format(tx.createdAt, 'dd MMM yyyy, HH:mm')}</td>
      <td>{tx.description}</td>
      <td className={tx.amount < 0 ? 'text-red-600' : 'text-green-600'}>
        {formatCurrency(tx.amount, tx.currency)}
      </td>
      <td>
        <TransactionStatusBadge status={tx.status} />
      </td>
    </tr>
  );
}
```

## API Client Pattern

```typescript
// lib/api/client.ts
class ApiClient {
  private baseUrl: string;

  constructor() {
    this.baseUrl = process.env.NEXT_PUBLIC_API_URL!;
  }

  private async request<T>(path: string, options?: RequestInit): Promise<T> {
    const token = getAccessToken(); // from auth context
    const response = await fetch(`${this.baseUrl}${path}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(token && { Authorization: `Bearer ${token}` }),
        ...options?.headers,
      },
    });

    if (response.status === 401) {
      // Token expired — attempt refresh
      const refreshed = await refreshToken();
      if (refreshed) return this.request<T>(path, options);
      redirectToLogin();
      throw new AuthError('Session expired');
    }

    if (!response.ok) {
      const error = await response.json();
      throw new ApiError(error.error.code, error.error.message, response.status);
    }

    const json = await response.json();
    return json.data as T; // unwrap standard { data, meta } envelope
  }

  // Type-safe methods
  getTransactions(accountId: string, page: number) {
    return this.request<PaginatedResponse<Transaction>>(
      `/api/accounts/${accountId}/transactions?page=${page}`
    );
  }
}

export const api = new ApiClient();
```

## Security Rules for Frontend

```
FE-SEC-01: Never store tokens in localStorage — use httpOnly cookies or in-memory with refresh
FE-SEC-02: Sanitize all user-generated content before rendering (DOMPurify for HTML content)
FE-SEC-03: CSP header must be configured (report-only first, then enforce)
FE-SEC-04: No PII in URL parameters — use POST or encrypted state
FE-SEC-05: Auto-logout on idle: 15 minutes for financial pages, 30 minutes for non-financial
FE-SEC-06: Clipboard copy of sensitive data: clear clipboard after 60 seconds
FE-SEC-07: Form autocomplete="off" for sensitive financial fields
FE-SEC-08: No console.log of PII in production (strip via build config)
```

## Testing Patterns

```typescript
// Test: form validation
describe('TransferForm', () => {
  it('shows error for amount exceeding limit', async () => {
    render(<TransferForm onSuccess={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Amount'), '60000');
    await userEvent.click(screen.getByRole('button', { name: /transfer/i }));
    expect(screen.getByText('Exceeds single transfer limit')).toBeInTheDocument();
  });

  it('masks account number in confirmation', async () => {
    // ... fill form ...
    expect(screen.getByText('****5678')).toBeInTheDocument();
  });
});

// Test: PII masking
describe('maskAccount', () => {
  it('masks all but last 4 digits', () => {
    expect(maskAccount('1234567890')).toBe('******7890');
  });
});

// Test: API error handling
describe('useTransfer', () => {
  it('sets form error on insufficient funds', async () => {
    server.use(
      http.post('/api/transfers', () =>
        HttpResponse.json(
          { error: { code: 'INSUFFICIENT_FUNDS', message: 'Insufficient funds' } },
          { status: 422 }
        )
      )
    );
    // ... assert form error appears ...
  });
});
```

## Common Mistakes to Avoid

1. **Storing JWT in localStorage** — XSS can steal it. Use httpOnly cookies or in-memory.
2. **Displaying raw API amounts** — API returns cents (minor units). Always divide by 100.
3. **Showing full PII by default** — mask everything, reveal on explicit user action.
4. **Missing loading/error states** — every data fetch needs loading skeleton and error boundary.
5. **Client-side-only validation** — always validate server-side too. Client validation is UX, not security.
6. **Forgetting keyboard navigation** — all interactive elements must be keyboard accessible.
7. **Fetching all transactions at once** — paginate with cursor-based or offset pagination.
8. **Console.log with PII** — strip all console.log in production builds.

import { useState } from 'react'
import { api } from './api/client'

type Transfer = {
  id: string
  source_account_number: string
  dest_account_number?: string | null
  dest_iban?: string | null
  amount: number
  currency: string
  status: string
  is_internal: boolean
  description?: string | null
  created_at: string
  updated_at: string
}

export default function App() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  const [sourceAccount, setSourceAccount] = useState('001200456789')
  const [destAccount, setDestAccount] = useState('001200987654')
  const [destIban, setDestIban] = useState('')
  const [amount, setAmount] = useState('2500000')
  const [isExternal, setIsExternal] = useState(false)

  const [transfer, setTransfer] = useState<Transfer | null>(null)
  const [queryId, setQueryId] = useState('')

  async function createTransfer() {
    setLoading(true)
    setError('')
    setMessage('')
    setTransfer(null)

    try {
      const idempotencyKey = `web-${Date.now()}`
      const correlationId = crypto.randomUUID()

      const body: Record<string, unknown> = {
        source_account_number: sourceAccount,
        amount: Number(amount),
        currency: 'IRR',
      }

      if (isExternal && destIban) {
        body.dest_iban = destIban
      } else if (destAccount) {
        body.dest_account_number = destAccount
      }

      const { data, error: apiError } = await api.POST('/transfers', {
        params: {
          header: {
            'Idempotency-Key': idempotencyKey,
            'X-Correlation-ID': correlationId,
          },
        },
        body: body as never,
      })

      if (apiError) {
        const problem = apiError as { code?: string; title?: string; detail?: string }
        setError(`${problem.code ?? 'ERROR'}: ${problem.title ?? ''} — ${problem.detail ?? ''}`)
      } else if (data) {
        setTransfer(data as unknown as Transfer)
        setMessage('Transfer created successfully.')
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setLoading(false)
    }
  }

  async function getTransfer() {
    if (!queryId) {
      setError('Enter a transfer ID.')
      return
    }
    setLoading(true)
    setError('')
    setMessage('')

    try {
      const { data, error: apiError } = await api.GET('/transfers/{id}', {
        params: { path: { id: queryId } },
      })

      if (apiError) {
        const problem = apiError as { code?: string; title?: string; detail?: string }
        setError(`${problem.code ?? 'ERROR'}: ${problem.title ?? ''} — ${problem.detail ?? ''}`)
      } else if (data) {
        setTransfer(data as unknown as Transfer)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main
      style={{
        maxWidth: 720,
        margin: '64px auto',
        padding: 24,
        fontFamily: 'system-ui, Arial, sans-serif',
        color: '#17212b',
      }}
    >
      <h1>S3 — Payment Orchestration</h1>
      <p>Frontend connected to <code>openapi/payment.yaml</code> via openapi-fetch.</p>

      <hr style={{ margin: '24px 0' }} />

      <h2>Create Transfer</h2>
      <div style={{ display: 'grid', gap: 8, marginBottom: 16 }}>
        <label>
          Source account number:
          <input
            value={sourceAccount}
            onChange={(e) => setSourceAccount(e.target.value)}
            style={{ width: '100%', padding: 6 }}
          />
        </label>
        <label>
          <input
            type="checkbox"
            checked={isExternal}
            onChange={(e) => setIsExternal(e.target.checked)}
          />
          {' '}External transfer (use IBAN instead of account number)
        </label>
        {isExternal ? (
          <label>
            Destination IBAN:
            <input
              value={destIban}
              onChange={(e) => setDestIban(e.target.value)}
              placeholder="IR430120000000000000000001"
              style={{ width: '100%', padding: 6 }}
            />
          </label>
        ) : (
          <label>
            Destination account number:
            <input
              value={destAccount}
              onChange={(e) => setDestAccount(e.target.value)}
              style={{ width: '100%', padding: 6 }}
            />
          </label>
        )}
        <label>
          Amount (IRR):
          <input
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            style={{ width: '100%', padding: 6 }}
          />
        </label>
        <button
          onClick={createTransfer}
          disabled={loading}
          style={{ padding: '8px 16px', fontSize: 16, cursor: 'pointer' }}
        >
          {loading ? 'Sending…' : 'Create Transfer'}
        </button>
      </div>

      <hr style={{ margin: '24px 0' }} />

      <h2>Query Transfer</h2>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <input
          value={queryId}
          onChange={(e) => setQueryId(e.target.value)}
          placeholder="Transfer UUID"
          style={{ flex: 1, padding: 6 }}
        />
        <button
          onClick={getTransfer}
          disabled={loading}
          style={{ padding: '8px 16px', cursor: 'pointer' }}
        >
          Get
        </button>
      </div>

      {message && <p style={{ color: '#067647' }}>{message}</p>}
      {error && <p role="alert" style={{ color: '#b42318' }}>{error}</p>}

      {transfer && (
        <div>
          <h3>Transfer</h3>
          <pre
            style={{
              padding: 20,
              background: '#eef2f6',
              borderRadius: 8,
              whiteSpace: 'pre-wrap',
              overflowX: 'auto',
            }}
          >
            {JSON.stringify(transfer, null, 2)}
          </pre>
        </div>
      )}
    </main>
  )
}

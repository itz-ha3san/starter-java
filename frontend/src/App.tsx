import { useEffect, useState } from 'react'

export default function App() {
  const [loading, setLoading] = useState(true)
  const [httpStatus, setHttpStatus] = useState<number | null>(null)
  const [response, setResponse] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    const controller = new AbortController()

    async function checkHealth() {
      try {
        const result = await fetch('/health', {
          signal: controller.signal,
          cache: 'no-store',
        })

        const body = await result.text()
        setHttpStatus(result.status)

        try {
          setResponse(JSON.stringify(JSON.parse(body), null, 2))
        } catch {
          setResponse(body)
        }

        if (!result.ok) {
          throw new Error(`Backend returned HTTP ${result.status}`)
        }
      } catch (cause) {
        if (controller.signal.aborted) return

        setError(
          cause instanceof Error ? cause.message : 'Request failed',
        )
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    }

    void checkHealth()

    return () => controller.abort()
  }, [])

  return (
    <main
      style={{
        maxWidth: 720,
        margin: '64px auto',
        padding: 24,
        fontFamily: 'Arial, sans-serif',
      }}
    >
      <h1>S3 — Service Health</h1>
      <p>Frontend: React + TypeScript + Vite</p>
      <p>
        Request: <code>GET /health</code>
      </p>
      <p>Backend: http://127.0.0.1:8080</p>

      {loading && <p>Checking backend…</p>}

      {httpStatus !== null && (
        <h2>HTTP status: {httpStatus}</h2>
      )}

      {response && (
        <pre
          style={{
            padding: 20,
            background: '#eef2f6',
            color: '#17212b',
            borderRadius: 8,
            whiteSpace: 'pre-wrap',
          }}
        >
          {response}
        </pre>
      )}

      {error && (
        <p role="alert" style={{ color: '#b42318' }}>
          Error: {error}
        </p>
      )}

      {!loading && !error && (
        <p style={{ color: '#067647' }}>
          Backend request completed successfully.
        </p>
      )}
    </main>
  )
}
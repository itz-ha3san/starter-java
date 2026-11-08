import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

// The API client is replaced at the component boundary. No backend is started.
const mock = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn() }))
vi.mock('./api/client', () => ({ api: { POST: mock.post, GET: mock.get } }))

describe('Create Transfer component', () => {
  it('shows the created transfer after a successful response (FE-05)', async () => {
    const user = userEvent.setup()
    const transfer = {
      id: '1f28e913-df36-4102-af49-f76d6141b707',
      amount: 2500000,
      currency: 'IRR',
      status: 'RISK_CHECKING',
      is_internal: true,
      created_at: '2026-11-08T12:00:00Z',
      updated_at: '2026-11-08T12:00:00Z',
    }
    mock.post.mockResolvedValueOnce({ data: transfer, error: undefined })
    render(<App />)

    await user.click(screen.getByRole('button', { name: 'Create Transfer' }))

    expect(await screen.findByText('Transfer created successfully.')).toBeInTheDocument()
    expect(screen.getByText(/1f28e913-df36-4102-af49-f76d6141b707/)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(mock.post).toHaveBeenCalledTimes(1)
    const [path, options] = mock.post.mock.calls[0]
    expect(path).toBe('/transfers')
    expect(options.body).toEqual({
      source_account_number: '001200456789',
      dest_account_number: '001200987654',
      amount: 2500000,
      currency: 'IRR',
    })
    expect(options.params.header['Idempotency-Key']).toMatch(/^web-\d+$/)
    expect(options.params.header['X-Correlation-ID']).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    )
  })

  it('shows Problem Details and no success message on failure (FE-06)', async () => {
    const user = userEvent.setup()
    mock.post.mockResolvedValueOnce({
      data: undefined,
      error: {
        type: 'https://errors.labbank.example/problem/validation_error',
        title: 'Input validation failed',
        status: 400,
        code: 'VALIDATION_ERROR',
        detail: 'Amount must be greater than zero',
        correlation_id: '67ec2289-c3b7-4dc0-a911-c67d66c351f3',
        instance: '/api/v1/transfers',
      },
    })
    render(<App />)

    const amount = screen.getByLabelText('Amount (IRR):')
    await user.clear(amount)
    await user.type(amount, '0')
    await user.click(screen.getByRole('button', { name: 'Create Transfer' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('VALIDATION_ERROR')
    expect(alert).toHaveTextContent('Input validation failed')
    expect(alert).toHaveTextContent('Amount must be greater than zero')
    expect(screen.queryByText('Transfer created successfully.')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Transfer', level: 3 })).not.toBeInTheDocument()
    await waitFor(() => expect(mock.post).toHaveBeenCalledTimes(1))
    expect(mock.post.mock.calls[0][1].body.amount).toBe(0)
  })
})

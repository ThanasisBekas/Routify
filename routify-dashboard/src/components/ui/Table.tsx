/**
 * Table — shared table primitive for Routify Dashboard.
 *
 * A composable set of sub-components (Table.Root, Table.Header, Table.Body,
 * Table.Row, Table.Head, Table.Cell) that enforce the platform's dark-theme
 * table styling consistently.
 *
 * Usage:
 * ```tsx
 * <Table.Root>
 *   <Table.Header>
 *     <Table.Row>
 *       <Table.Head>Name</Table.Head>
 *       <Table.Head>Status</Table.Head>
 *     </Table.Row>
 *   </Table.Header>
 *   <Table.Body>
 *     <Table.Row>
 *       <Table.Cell>my-route</Table.Cell>
 *       <Table.Cell><Badge color="emerald">Active</Badge></Table.Cell>
 *     </Table.Row>
 *   </Table.Body>
 * </Table.Root>
 * ```
 */
import React from 'react'
import { cn } from '../../lib/utils'

function Root({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn('overflow-x-auto rounded-xl border border-white/[0.06]', className)}>
      <table className="w-full text-sm">{children}</table>
    </div>
  )
}

function Header({ children }: { children: React.ReactNode }) {
  return (
    <thead className="bg-white/[0.02] border-b border-white/[0.06]">
      {children}
    </thead>
  )
}

function Body({ children }: { children: React.ReactNode }) {
  return <tbody className="divide-y divide-white/[0.04]">{children}</tbody>
}

function Row({
  children,
  className,
  onClick,
}: {
  children: React.ReactNode
  className?: string
  onClick?: () => void
}) {
  return (
    <tr
      className={cn(
        'transition-colors',
        onClick && 'cursor-pointer hover:bg-white/[0.03]',
        className,
      )}
      onClick={onClick}
    >
      {children}
    </tr>
  )
}

function Head({ children, className }: { children?: React.ReactNode; className?: string }) {
  return (
    <th
      className={cn(
        'px-4 py-3 text-left text-[11px] font-bold text-gray-500 uppercase tracking-wider',
        className,
      )}
    >
      {children}
    </th>
  )
}

function Cell({ children, className }: { children?: React.ReactNode; className?: string }) {
  return (
    <td className={cn('px-4 py-3 text-gray-300', className)}>
      {children}
    </td>
  )
}

export const Table = { Root, Header, Body, Row, Head, Cell }


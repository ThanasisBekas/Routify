import { cn } from '../../../lib/utils'
import type { RouteEnvironment } from '../../../types'

const ENV_CONFIG: Record<RouteEnvironment, { label: string; color: string; icon: string }> = {
  PRODUCTION: {
    label: 'Production',
    color: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
    icon: '🟢',
  },
  STAGING: {
    label: 'Staging',
    color: 'bg-amber-500/10 text-amber-400 border-amber-500/20',
    icon: '🟡',
  },
}

interface Props {
  environment: RouteEnvironment
  className?: string
}

export default function EnvironmentBadge({ environment, className }: Props) {
  const cfg = ENV_CONFIG[environment] ?? ENV_CONFIG.PRODUCTION
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-[10px] font-semibold px-1.5 py-0.5 rounded-full border',
        cfg.color,
        className,
      )}
    >
      <span>{cfg.icon}</span>
      {cfg.label}
    </span>
  )
}


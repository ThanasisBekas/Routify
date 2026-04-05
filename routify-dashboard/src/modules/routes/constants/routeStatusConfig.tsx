import { CheckCircle, Clock, Pause, Archive } from 'lucide-react'
import type { RouteStatus } from '../../../types'

export const STATUS_CONFIG: Record<
  RouteStatus,
  { label: string; color: string; dot: string; glow: string; icon: React.ReactNode }
> = {
  DRAFT: {
    label: 'Draft',
    color: 'text-amber-400 bg-amber-400/10 border-amber-400/20',
    dot: 'bg-amber-400',
    glow: 'shadow-amber-500/10',
    icon: <Clock className="w-3 h-3" />,
  },
  ACTIVE: {
    label: 'Active',
    color: 'text-emerald-400 bg-emerald-400/10 border-emerald-400/20',
    dot: 'bg-emerald-400',
    glow: 'shadow-emerald-500/15',
    icon: <CheckCircle className="w-3 h-3" />,
  },
  DISABLED: {
    label: 'Disabled',
    color: 'text-gray-400 bg-gray-400/10 border-gray-400/20',
    dot: 'bg-gray-500',
    glow: '',
    icon: <Pause className="w-3 h-3" />,
  },
  ARCHIVED: {
    label: 'Archived',
    color: 'text-red-400 bg-red-400/10 border-red-400/20',
    dot: 'bg-red-500',
    glow: '',
    icon: <Archive className="w-3 h-3" />,
  },
}

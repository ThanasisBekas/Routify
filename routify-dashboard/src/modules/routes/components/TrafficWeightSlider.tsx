import { cn } from '../../../lib/utils'

interface TrafficWeightSliderProps {
  value: number
  onChange: (value: number) => void
  min?: number
  max?: number
  disabled?: boolean
  className?: string
}

export function TrafficWeightSlider({
  value,
  onChange,
  min = 5,
  max = 50,
  disabled = false,
  className,
}: TrafficWeightSliderProps) {
  return (
    <div className={cn('space-y-2', className)}>
      <div className="flex items-center justify-between text-sm">
        <span className="text-muted-foreground">Primary: {100 - value}%</span>
        <span className="font-medium text-amber-600">Canary: {value}%</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        disabled={disabled}
        className="w-full accent-amber-600"
      />
      <div className="flex justify-between">
        <div className="h-2 rounded-l-full bg-blue-500" style={{ width: `${100 - value}%` }} />
        <div className="h-2 rounded-r-full bg-amber-500" style={{ width: `${value}%` }} />
      </div>
    </div>
  )
}

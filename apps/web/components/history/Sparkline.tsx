/** A small trend line with a text alternative. Renders nothing below two points. */
export function Sparkline({ values, noun }: { values: number[]; noun: string }) {
  if (values.length < 2) return null;
  const width = 80;
  const height = 24;
  const min = Math.min(...values);
  const range = Math.max(...values) - min || 1;
  const points = values
    .map((value, index) => `${(index / (values.length - 1)) * width},${height - 2 - ((value - min) / range) * (height - 4)}`)
    .join(" ");
  const first = values[0];
  const last = values.at(-1)!;
  const direction = last > first ? "rose" : last < first ? "fell" : "stayed";
  const label = direction === "stayed"
    ? `Score stayed at ${last} over ${values.length} ${noun}`
    : `Score ${direction} from ${first} to ${last} over ${values.length} ${noun}`;
  return (
    <svg role="img" aria-label={label} width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="shrink-0 text-primary">
      <title>{label}</title>
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

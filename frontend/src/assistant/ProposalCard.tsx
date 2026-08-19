interface Props {
  explanation: string;
  canApply: boolean;
  previewActive: boolean;
  onShowMe: () => void;
  onApply: () => void;
  onCancel: () => void;
}

/**
 * The change order: what will happen, in one sentence, and the decision.
 * The checks that earned this card are listed above it in the activity trail.
 */
export function ProposalCard({ explanation, canApply, previewActive, onShowMe, onApply, onCancel }: Props) {
  return (
    <div className="proposal" role="group" aria-label="Proposed change">
      <div className="proposal-head">
        <span className="eyebrow">Change order</span>
        <span className="eyebrow">{canApply ? "Checked" : "Needs attention"}</span>
      </div>
      <div className="proposal-body">
        <p className="sentence">{explanation}</p>
      </div>
      <div className="proposal-actions">
        <button className="btn btn-secondary" onClick={onShowMe} disabled={!canApply || previewActive}>
          {previewActive ? "Shown on canvas" : "Show me"}
        </button>
        <button className="btn btn-ghost" onClick={onCancel}>
          Cancel
        </button>
        <button className="btn btn-primary" onClick={onApply} disabled={!canApply}>
          Apply change
        </button>
      </div>
    </div>
  );
}

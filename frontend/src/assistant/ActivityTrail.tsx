export type TaskState = "waiting" | "running" | "done" | "failed";

export interface Task {
  id: string;
  label: string;
  detail?: string;
  state: TaskState;
}

/**
 * What the assistant is doing, one line per task.
 *
 * <p>Every row reflects work that really ran: the first rows track the request as it
 * progresses, and the check rows carry the wording the validation gates themselves
 * returned. A row is never marked done before the thing it names has happened.
 */
export function ActivityTrail({ tasks }: { tasks: Task[] }) {
  return (
    <ol className="trail" aria-label="What the assistant is doing">
      {tasks.map((task) => (
        <li key={task.id} className={`trail-item ${task.state}`}>
          <span className="trail-marker" aria-hidden>
            {task.state === "running" && <span className="spinner" />}
            {task.state === "done" && "✓"}
            {task.state === "failed" && "✕"}
          </span>
          <span className="trail-text">
            <span className="trail-label">{task.label}</span>
            {task.detail && <span className="trail-detail">{task.detail}</span>}
          </span>
        </li>
      ))}
    </ol>
  );
}

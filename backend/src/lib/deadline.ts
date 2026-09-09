// Bound both fetches and body reads; aborting fetch alone is insufficient for
// a provider that keeps sending SSE comments without ever producing text.
export class RequestDeadline {
  readonly controller = new AbortController();
  private idleTimer: ReturnType<typeof setTimeout>;
  private readonly totalTimer: ReturnType<typeof setTimeout>;
  private readonly forwardAbort = () => this.controller.abort(this.parent?.reason);

  constructor(
    private readonly idleMs: number,
    totalMs: number,
    private readonly parent?: AbortSignal
  ) {
    this.idleTimer = setTimeout(() => this.expire(), idleMs);
    this.totalTimer = setTimeout(() => this.expire(), totalMs);
    if (parent?.aborted) this.forwardAbort();
    else parent?.addEventListener("abort", this.forwardAbort, { once: true });
  }

  get signal(): AbortSignal { return this.controller.signal; }

  touch(idleMs = this.idleMs): void {
    clearTimeout(this.idleTimer);
    this.idleTimer = setTimeout(() => this.expire(), idleMs);
  }

  private expire(): void {
    this.controller.abort(new DOMException("The provider stopped responding.", "TimeoutError"));
  }

  async run<T>(operation: Promise<T>): Promise<T> {
    // Observe even an already-aborted operation so late rejections are handled.
    if (this.signal.aborted) {
      void operation.catch(() => {});
      throw this.signal.reason;
    }
    let onAbort = () => {};
    const aborted = new Promise<never>((_, reject) => {
      onAbort = () => reject(this.signal.reason);
      if (this.signal.aborted) onAbort();
      else this.signal.addEventListener("abort", onAbort, { once: true });
    });
    try { return await Promise.race([operation, aborted]); }
    finally { this.signal.removeEventListener("abort", onAbort); }
  }

  dispose(): void {
    clearTimeout(this.idleTimer);
    clearTimeout(this.totalTimer);
    this.parent?.removeEventListener("abort", this.forwardAbort);
  }
}

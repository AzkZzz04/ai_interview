import { setupServer } from "msw/node";
import { createHandlers } from "./handlers";
import { createMockStore, type MockStoreOptions } from "./store";

/** A mock backend for vitest with an in-memory store and a controllable clock. */
export function createMockServer(options: MockStoreOptions = {}) {
  const store = createMockStore(options);
  const server = setupServer(...createHandlers(store));
  return { store, server };
}

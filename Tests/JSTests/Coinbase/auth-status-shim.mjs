import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const SCRIPT_CANDIDATES = [
  "../../../zerohashsdk/src/main/assets/automation/auth-status.js",
  "../../../connectsdk/src/main/assets/automation/auth-status.js",
  "../../../Sources/ZerohashSDK/Platforms/Coinbase/auth-status.js",
  "../../../Sources/ConnectSDK/Platforms/Coinbase/auth-status.js"
];
const HELPER_CANDIDATES = [
  "../../../zerohashsdk/src/main/assets/automation/shared-dom-helpers.js",
  "../../../connectsdk/src/main/assets/automation/shared-dom-helpers.js",
  "../../../Sources/ZerohashSDK/AutomationScripts/shared-dom-helpers.js",
  "../../../Sources/ConnectSDK/AutomationScripts/shared-dom-helpers.js"
];
const SOURCE = readFirstExisting(SCRIPT_CANDIDATES, "auth-status.js");
const HELPERS_SOURCE = readFirstExisting(HELPER_CANDIDATES, "shared-dom-helpers.js");

export const TIME_SCALE = 100;
export const PROFILE_URL = "https://www.coinbase.com/graphql/query?operationName=userQuery";
export const PROFILE_QUERY =
  "query userQuery { viewer { userProperties { uuid personalDetails { legalName { firstName lastName } } } } }";
export const FICTIONAL_USER = { uuid: "8b0c2f4e-1a2b-4c3d-9e8f-0a1b2c3d4e5f", firstName: "Jane Mary", lastName: "Doe" };
const TESTID_SELECTOR = /^\[data-testid="(.*)"\]$/;
const NAV_CHROME_SELECTOR = "header, nav";

function readFirstExisting(candidates, fileName) {
  const paths = candidates.map((relative) => fileURLToPath(new URL(relative, import.meta.url)));
  const path = paths.find((candidate) => existsSync(candidate));
  if (!path) {
    throw new Error(`auth-status-shim: ${fileName} not found`);
  }

  return readFileSync(path, "utf8");
}

function legalNameOrDefault(legalName, firstName, lastName) {
  if (legalName === undefined) {
    return { firstName, lastName };
  }

  return legalName;
}

function graphqlErrorEntry(code, message) {
  if (!code) {
    return { message };
  }

  return { message, extensions: { code } };
}

function nextResponse(queue) {
  if (queue.length > 1) {
    return queue.shift();
  }

  return queue[0];
}

function responseText(descriptor) {
  if (descriptor.text !== undefined) {
    return descriptor.text;
  }

  return JSON.stringify(descriptor.body);
}

function requestBody(init) {
  if (init.body === undefined) {
    return undefined;
  }

  return JSON.parse(init.body);
}

function scaledDelay(ms) {
  if (!ms) {
    return 0;
  }

  return Math.max(0, ms / TIME_SCALE);
}

function scaledSetTimeout(callback, ms) {
  return setTimeout(callback, scaledDelay(ms));
}

function pageAbortController(noAbortController) {
  if (noAbortController) {
    return undefined;
  }

  return AbortController;
}

function asReceivedByNative(value) {
  return JSON.parse(JSON.stringify(value));
}

function abortableHang(signal) {
  return new Promise((_resolve, reject) => {
    signal.addEventListener("abort", () => {
      const aborted = new Error("aborted");
      aborted.name = "AbortError";
      reject(aborted);
    });
  });
}

function respond(descriptor, signal) {
  if (descriptor.throws) {
    return Promise.reject(descriptor.throws);
  }

  if (descriptor.hangs) {
    return abortableHang(signal);
  }

  return Promise.resolve({
    status: descriptor.status,
    ok: descriptor.status >= 200 && descriptor.status < 300,
    headers: { get: () => null },
    text: async () => responseText(descriptor)
  });
}

export const userQuery = ({
  uuid = FICTIONAL_USER.uuid,
  firstName = FICTIONAL_USER.firstName,
  lastName = FICTIONAL_USER.lastName,
  legalName,
  extraProps = {}
} = {}) => ({
  status: 200,
  body: {
    data: {
      viewer: {
        userProperties: {
          uuid,
          personalDetails: { legalName: legalNameOrDefault(legalName, firstName, lastName) },
          ...extraProps
        }
      }
    }
  }
});
export const graphqlError = ({ code, message = "Something failed" } = {}) => ({
  status: 200,
  body: { data: null, errors: [graphqlErrorEntry(code, message)] }
});
export const httpError = (status) => ({ status, body: {} });
export const rawText = (text) => ({ status: 200, text });
export const networkFailure = () => ({ throws: new TypeError("Failed to fetch") });
export const hangs = () => ({ hangs: true });

export async function runAuthStatus({
  href = "https://www.coinbase.com/home",
  testids = [],
  signedOutLink = false,
  responses = [],
  telemetryThrows = false,
  telemetryGetterThrows = false,
  noAbortController = false,
  profileBudgetMs
} = {}) {
  const calls = [];
  const events = [];
  const queue = responses.slice();
  const realNow = Date.now;
  const t0 = realNow();
  const present = new Set(testids);

  const document = {
    querySelector(selector) {
      const testidMatch = TESTID_SELECTOR.exec(selector);
      if (testidMatch && present.has(testidMatch[1])) {
        return {};
      }

      if (selector === NAV_CHROME_SELECTOR && signedOutLink) {
        return { querySelector: () => ({}) };
      }

      return null;
    }
  };

  function fetch(url, init = {}) {
    calls.push({
      url,
      method: init.method,
      credentials: init.credentials,
      cache: init.cache,
      headers: { ...init.headers },
      body: requestBody(init)
    });
    const descriptor = nextResponse(queue);
    if (!descriptor) {
      return Promise.reject(new Error("auth-status-shim: unexpected fetch " + url));
    }

    return respond(descriptor, init.signal);
  }

  const window = {
    __zhTelemetry: {
      emit(row) {
        if (telemetryThrows) {
          throw new Error("telemetry broke");
        }

        events.push(asReceivedByNative(row));
      }
    }
  };

  if (telemetryGetterThrows) {
    Object.defineProperty(window, "__zhTelemetry", {
      get() {
        throw new Error("telemetry getter broke");
      }
    });
  }

  const ctx = vm.createContext({
    window,
    document,
    fetch,
    location: { href },
    URL,
    AbortController: pageAbortController(noAbortController),
    setTimeout: scaledSetTimeout,
    clearTimeout,
    Date: { now: () => t0 + (realNow() - t0) * TIME_SCALE },
    console
  });
  if (profileBudgetMs !== undefined) {
    ctx.params = { profileDeadlineMs: t0 + profileBudgetMs };
  }

  vm.runInContext(HELPERS_SOURCE, ctx);
  const raw = await vm.runInContext(SOURCE, ctx);
  const elapsedMs = (realNow() - t0) * TIME_SCALE;

  return { result: asReceivedByNative(raw), calls, events, elapsedMs };
}

// Loads get-balance.js in a Node VM and re-exports its parse layer, so the row
// shape can be asserted without a browser or the network.
//
// The file is an IIFE that publishes `module.exports` and returns early when it
// finds a CommonJS `module`, so the sandbox has to provide one — otherwise it
// falls through to its WebView entry point and tries to fetch.
//
// Fixtures carry only the fields the parser reads.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const SRC = fileURLToPath(
  new URL("../../../zerohashsdk/src/main/assets/automation/get-balance.js", import.meta.url)
);
const SOURCE = readFileSync(SRC, "utf8");

/** ECMAScript intrinsics come free inside a VM context; these are the only
 *  host globals get-balance.js touches on the parse path. */
const hostGlobals = () => ({ console });

export function loadBalance() {
  const module = { exports: {} };
  const sandbox = { module, exports: module.exports, ...hostGlobals() };
  // `bc()` and the entry point read `window`; self-reference keeps both benign.
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(SOURCE, sandbox);
  return module.exports;
}

// ─── fixtures ────────────────────────────────────────────────────────────────

/**
 * One `cryptoAssets` edge. `staked` is Coinbase's raw
 * `staking.summary.totalStakedPercent` — a decimal in 0–1, NOT a percent. Omit it
 * for an asset that cannot be staked.
 */
function cryptoEdge({ symbol, name, amount, notional, staked }) {
  const asset = { asset: { displaySymbol: symbol, name } };
  if (staked !== undefined) asset.staking = { summary: { totalStakedPercent: staked } };
  return {
    node: {
      totalBalanceCrypto: { amount },
      totalBalanceFiat: { amount: notional },
      asset
    }
  };
}

/**
 * One `cashAssets` edge. `accounts` is the per-account breakdown Coinbase returns
 * under the ViewerAsset; omit it to get a row with no breakdown at all.
 */
function cashEdge({ symbol, name, amount, notional, accounts }) {
  return {
    node: {
      totalBalanceCrypto: { amount },
      totalBalanceFiat: { amount: notional },
      asset: {
        asset: { displaySymbol: symbol, name },
        ...(accounts === undefined ? {} : { accounts })
      }
    }
  };
}

export function cryptoResponse(rows) {
  return { data: { viewer: { cryptoAssets: { edges: rows.map(cryptoEdge) } } } };
}

export function cashResponse(rows) {
  return { data: { viewer: { cashAssets: { edges: rows.map(cashEdge) } } } };
}

export function oneCryptoRow(parseConnection, row) {
  const parsed = parseConnection(cryptoResponse([row]), "cryptoAssets", "CryptoQuery", "USD");
  return parsed.balances[0];
}

export function oneCashRow(parseConnection, row) {
  const parsed = parseConnection(cashResponse([row]), "cashAssets", "CashQuery", "USD");
  return parsed.balances[0];
}

export function wallet(native) {
  return { type: "WALLET", allowWithdrawals: true, totalBalanceInNativeCurrency: { value: native } };
}

export function lent(native) {
  return {
    type: "RETAIL_DEFI_LEND",
    allowWithdrawals: false,
    totalBalanceInNativeCurrency: { value: native }
  };
}

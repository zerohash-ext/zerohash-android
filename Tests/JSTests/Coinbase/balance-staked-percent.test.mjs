// `totalStakedPercent` is the slice of a row that cannot be withdrawn: consumers
// compute `withdrawable = amount * (1 - totalStakedPercent / 100)`. Get it wrong
// and we either hide funds the user can spend or offer funds the exchange will
// refuse to send.
//
// The contract is `string | null` — a decimal percent, never a percent sign and
// never a non-numeric string. See docs/contracts/operations/get-balance.md.
import assert from "node:assert";
import { test } from "node:test";
import { lent, loadBalance, oneCashRow, oneCryptoRow, wallet } from "./balance-shim.mjs";

const { parseConnection } = loadBalance();

// ─── crypto: staking ─────────────────────────────────────────────────────────

test("a staked crypto asset scales Coinbase's 0-1 fraction to a percent", () => {
  const row = oneCryptoRow(parseConnection, {
    symbol: "ETH", name: "Ethereum", amount: "10", notional: "25000", staked: "0.5"
  });
  assert.strictEqual(row.totalStakedPercent, "50");
});

test("a crypto asset that cannot be staked reports null", () => {
  const row = oneCryptoRow(parseConnection, {
    symbol: "BTC", name: "Bitcoin", amount: "0.5321", notional: "34120.55"
  });
  assert.strictEqual(row.totalStakedPercent, null);
});

test("a fully staked crypto asset reports 100", () => {
  const row = oneCryptoRow(parseConnection, {
    symbol: "ETH", name: "Ethereum", amount: "10", notional: "25000", staked: "1"
  });
  assert.strictEqual(row.totalStakedPercent, "100");
});

// A blank or junk value used to come out as the string "NaN", which is neither a
// number nor null. Absent data has to read as absent.
test("a non-numeric staking percent reports null, not the string NaN", () => {
  for (const staked of ["", "abc", "null"]) {
    const row = oneCryptoRow(parseConnection, {
      symbol: "SOL", name: "Solana", amount: "5", notional: "900", staked
    });
    assert.strictEqual(
      row.totalStakedPercent, null,
      `raw ${JSON.stringify(staked)} must not survive into the row`
    );
  }
});

// ─── cash: DeFi Lend ─────────────────────────────────────────────────────────

// Cash has no staking, but a cash asset CAN be lent out via DeFi Lend, and lent
// funds cannot be withdrawn. Reported through the same field.
test("a cash asset lent via DeFi Lend reports the locked fraction", () => {
  const row = oneCashRow(parseConnection, {
    symbol: "USDC", name: "USD Coin", amount: "1000", notional: "1000",
    accounts: [wallet("600"), lent("400")]
  });
  assert.strictEqual(row.totalStakedPercent, "40");
});

test("a fully lent cash asset reports 100", () => {
  const row = oneCashRow(parseConnection, {
    symbol: "USDC", name: "USD Coin", amount: "1000", notional: "1000",
    accounts: [lent("1000")]
  });
  assert.strictEqual(row.totalStakedPercent, "100");
});

test("a cash asset with nothing lent reports null", () => {
  const row = oneCashRow(parseConnection, {
    symbol: "USDC", name: "USD Coin", amount: "1000", notional: "1000",
    accounts: [wallet("1000")]
  });
  assert.strictEqual(row.totalStakedPercent, null);
});

// Not a confident "nothing is locked", but null is the only honest answer.
test("a cash asset with no account breakdown reports null", () => {
  const row = oneCashRow(parseConnection, {
    symbol: "USDC", name: "USD Coin", amount: "1000", notional: "1000"
  });
  assert.strictEqual(row.totalStakedPercent, null);
});

// A junk balance must not poison the ratio: a zero in the total would overstate
// the locked share.
test("an account with an unusable balance is left out of the ratio", () => {
  const row = oneCashRow(parseConnection, {
    symbol: "USDC", name: "USD Coin", amount: "1000", notional: "1000",
    accounts: [
      wallet("500"),
      lent("500"),
      { type: "WALLET", allowWithdrawals: true, totalBalanceInNativeCurrency: { value: "abc" } }
    ]
  });
  assert.strictEqual(row.totalStakedPercent, "50");
});

// ─── the rest of the row must not shift ──────────────────────────────────────

test("the staking fields do not disturb the rest of the row", () => {
  const row = oneCryptoRow(parseConnection, {
    symbol: "ETH", name: "Ethereum", amount: "10", notional: "25000", staked: "0.5"
  });
  assert.strictEqual(row.key, "ETH");
  assert.strictEqual(row.label, "Ethereum");
  assert.strictEqual(row.amount, "10");
  assert.strictEqual(row.notional, "25000");
  assert.strictEqual(row.currency, "USD");
  assert.strictEqual(row.precision, null);
  assert.match(row.extractedAt, /^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/);
});

import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import vm from 'node:vm';

// Exercise the scripts and polling methods taken directly from production source.
const source = readFileSync(new URL('../app/src/main/java/com/lyko/autosign/HookEntry.java', import.meta.url), 'utf8');
const scripts = Object.fromEntries([...source.matchAll(/private static final String (OBSERVE_SCRIPT|CLICK_SCRIPT) =([\s\S]*?);\n/g)]
  .map(([, name, expression]) => [name, [...expression.matchAll(/"(?:\\.|[^"\\])*"/g)].map(m => JSON.parse(m[0])).join('')]));
let now = new Date(2026, 10, 1, 8).getTime();
let clicks = 0;
const button = {
  textContent: '立即签到', disabled: false, ariaDisabled: false,
  hasAttribute: () => button.disabled,
  getAttribute: () => button.ariaDisabled ? 'true' : null,
  click: () => clicks++,
};
const context = vm.createContext({
  location: { hash: '#/pages/sign/index' }, window: {},
  document: { querySelector: () => button },
  Date: class extends Date {
    constructor() { super(now); }
    static now() { return now; }
  },
});
const observe = () => vm.runInContext(scripts.OBSERVE_SCRIPT, context);
const click = () => vm.runInContext(scripts.CLICK_SCRIPT, context);
assert.equal(observe(), 3);
button.textContent = ' 立即\n签到 ';
assert.equal(observe(), 3, 'Whitespace must not hide the button');
button.disabled = true;
assert.equal(click(), 0);
button.disabled = false;
button.ariaDisabled = true;
assert.equal(click(), 0);
button.ariaDisabled = false;
context.location.hash = '#/pages/home';
assert.equal(click(), -1);
context.location.hash = '#/pages/sign/index';
assert.equal(click(), 1);
assert.equal(observe(), 3, 'Dispatching a click is not signed confirmation');
assert.equal(click(), 4, 'Do not double click while waiting for the result');
now += 15_000;
assert.equal(click(), 1, 'Retry an unconfirmed click after the cooldown');
now += 15_000;
assert.equal(click(), 1);
now += 15_000;
assert.equal(click(), 5, 'Bound retries in this document');
assert.equal(clicks, 3);
button.textContent = '已签到';
assert.equal(observe(), 2);
assert.equal(click(), 2);
assert.equal(clicks, 3, 'Never click an already signed button');
button.textContent = '立即签到';
now += 24 * 60 * 60 * 1000;
assert.equal(click(), 1, 'A new day has a fresh attempt budget');
context.document.querySelector = () => null;
assert.equal(observe(), 0);
console.log('PASS: DOM recognition, click cooldown, retries, day rollover, confirmed state');

const methods = source.slice(source.indexOf('    private static void watch(WebView'), source.lastIndexOf('\n}'));
const constants = ['MAX_POLLS', 'POLL_DELAY_MS', 'STATUS_SETTLE_MS'].map(name =>
  source.match(new RegExp(`private static final (?:int|long) ${name} = [^;]+;`))[0]).join('\n');
const harness = `
import java.util.*;
import java.util.function.Consumer;
public class PollerRegression {
  ${constants}
  static final String OBSERVE_SCRIPT = "observe", CLICK_SCRIPT = "click";
  static final Map<WebView, Boolean> WATCHING = new WeakHashMap<>();
  static final Handler MAIN = new Handler();
  static Handler mainHandler() { return MAIN; }
  static long now = 1000;
  static final List<String> logs = new ArrayList<>();
  static class SystemClock { static long uptimeMillis() { return now; } }
  static class XposedBridge { static void log(String message) { logs.add(message); } }
  static class View { static final int VISIBLE = 0; }
  static class ViewGroup extends View {
    final List<View> children = new ArrayList<>();
    int getChildCount() { return children.size(); }
    View getChildAt(int i) { return children.get(i); }
  }
  static class WebView extends ViewGroup {
    String state = "3";
    String url = "/pages/sign/index";
    boolean shown = true;
    int clicks;
    boolean isShown() { return shown; }
    int getWindowVisibility() { return VISIBLE; }
    String getUrl() { return url; }
    void evaluateJavascript(String script, Consumer<String> result) {
      result.accept(script.equals(OBSERVE_SCRIPT) ? state
          : !state.equals("3") ? state : clicks >= 3 ? "5" : clicked());
    }
    String clicked() { clicks++; return "1"; }
  }
  static class Handler {
    record Event(long at, Runnable runnable) {}
    final PriorityQueue<Event> queue = new PriorityQueue<>(Comparator.comparingLong(Event::at));
    void postDelayed(Runnable runnable, long delay) { queue.add(new Event(now + delay, runnable)); }
    void advance(long duration) {
      long end = now + duration;
      while (!queue.isEmpty() && queue.peek().at() <= end) {
        Event e = queue.remove(); now = e.at(); e.runnable().run();
      }
      now = end;
    }
  }
  static boolean isSignUrl(String url) { return url != null && url.contains("/pages/sign/index"); }
  ${methods}
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message + "; logs=" + logs);
  }
  static WebView fresh() {
    MAIN.queue.clear(); WATCHING.clear(); logs.clear(); now = 1000;
    WebView view = new WebView(); watch(view); return view;
  }
  public static void main(String[] args) {
    WebView view = fresh();
    view.state = "0"; MAIN.advance(90_000); view.state = "3"; MAIN.advance(17_000);
    check(view.clicks == 1, "Slow loading past the old 80s timeout must still click");
    check(WATCHING.containsKey(view), "Click dispatch must keep observing");
    view.state = "2"; MAIN.advance(17_000);
    check(!WATCHING.containsKey(view), "Stable signed state must finish");
    check(logs.stream().anyMatch(s -> s.contains("sign success confirmed")), "Must log confirmed result");

    view = fresh(); view.state = "2"; MAIN.advance(1600);
    check(WATCHING.containsKey(view), "Transient cached signed state must not stop detection");
    view.state = "3"; MAIN.advance(17_000);
    check(view.clicks == 1, "Unsigned state after hydration must be handled");

    view = fresh(); view.shown = false; MAIN.advance(20_000);
    check(view.clicks == 0, "Hidden views must not click");
    view.shown = true; MAIN.advance(8000);
    check(view.clicks == 0, "Visibility must start a fresh settling interval");
    MAIN.advance(9000); check(view.clicks == 1, "Foreground view must resume detection");

    view = fresh(); view.state = "0"; MAIN.advance(182_000);
    check(!WATCHING.containsKey(view), "Expired poller must release its watch registration");
    view.state = "3";
    ViewGroup root = new ViewGroup(); root.children.add(view); watchViews(root); MAIN.advance(17_000);
    check(view.clicks == 1, "Rescanning a cached WebView must restart expired polling");

    view = fresh(); MAIN.advance(65_000);
    check(view.clicks == 3, "Unconfirmed attempts must be limited");
    check(!WATCHING.containsKey(view), "Exhausted attempts must stop");
    check(logs.stream().noneMatch(s -> s.contains("success confirmed")), "Attempts cannot be reported as success");
    System.out.println("PASS: slow loading, hydration, foreground return, cached view recovery, result confirmation, retry limit");
  }
}
`;
const temp = mkdtempSync(join(tmpdir(), 'lyko-sign-tests-'));
try {
  writeFileSync(join(temp, 'PollerRegression.java'), harness);
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  const result = spawnSync(java, [join(temp, 'PollerRegression.java')], { encoding: 'utf8' });
  process.stdout.write(result.stdout ?? '');
  process.stderr.write(result.stderr ?? '');
  assert.equal(result.status, 0, 'Production polling regression checks failed');
} finally {
  rmSync(temp, { recursive: true, force: true });
}

# M7 — running notes

Not a death report. The report is written when M7 lands on hardware; this is
the log it gets written from, so findings are not reconstructed later.

## Failures found before build

**`maxOfOrNull` over a nullable selector.** `[pat]` Caught by Pat reading the
diff, 2026-09-19, in `ScanViewModel.describeFolder`:

```kotlin
// shipped in b599c01, does not compile
modifiedAt = stats.maxOfOrNull { it.modifiedAtEpochMs }
// correct
modifiedAt = stats.mapNotNull { it.modifiedAtEpochMs }.maxOrNull()
```

`FileMetadata.modifiedAtEpochMs` is `Long?`. `maxOfOrNull` has three
overloads — `Double`, `Float`, and `R : Comparable<R>` — and a `Long?`
selector matches none of them unambiguously. `[test]` Reproduced against a
real compiler in a four-line scratch project: *"Overload resolution ambiguity
between candidates"*, listing all three. The fix compiles.

The corrected form is also the behaviour wanted independently of the compiler:
a file with no recorded timestamp should not decide its folder's.

`[inferred]` **Why the hand review missed it.** M6's report already named the
general failure — a hand review checks that code is consistent with itself,
not that its symbols resolve. This is a harder instance of the same thing:
the symbol exists, the receiver is right, the lambda is right, and the call
is still invalid on a generic bound that is nowhere visible in the line.
Nothing short of a compiler catches that.

`[inferred]` **What would have caught it here.** `describeFolder` is a
suspend function over `StorageGateway`, so it cannot enter the pure-Kotlin
scratch module, and every expression inside it is therefore unverified. The
picker logic it feeds *is* tested — 18 tests — which is exactly why the bug
landed in the adapter rather than in the logic. The scratch-module discipline
pushes bugs to the boundary; it does not remove them.

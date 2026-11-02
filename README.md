# Finance-Manager

An offline Android app that reads your M-Pesa and bank SMS and turns them into a personal finance tracker for Kenya.
Everything stays on the phone: there is no server, no account, and no data leaves the device.

## Features

- **Dashboard**: money available now (M-Pesa, Pochi and bank accounts), today's and this month's money in and out,
  a weekly allowance, and what's due this week.
- **Automatic categories** from M-Pesa, Equity, I&M, Co-op, KCB, Stanbic and Absa SMS. Tap any transaction to recategorise it,
  for that one payment or for everything from the same sender or account.
- **Loans**: Fuliza, M-Shwari, KCB M-PESA, Hustler/Bridge, Equity and others are tracked from SMS. Private loans can be added by hand,
  including savings-backed loans. Due-date reminders and loan-cycling warnings.
- **Owed to you**: money you've lent out is matched to repayments automatically.
- **Side gigs**: tag money in as side-gig pay, straight from the notification when it arrives. Track pending pay and get
  follow-ups when it's late.
- **Business**: stock bought, expected profit, and a "is it worth borrowing for this deal?" checker.
- **Own transfers**: money moved between your own accounts isn't counted as spending.
- **Uber/Bolt rides** captured from notifications and matched to M-Pesa payments to drivers.
- **Daily summary** at 11 PM.
- **Privacy**: fingerprint lock (with phone PIN as fallback), and a button to blur balances when others can see your screen.

## Build

Requires JDK 17+ and the Android SDK.

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

## Your personal set-up (optional)

On first launch the app copies a few defaults from `app/src/main/assets/personal.json` if that file exists.
It is **git-ignored**, so your details never end up in the repo. Copy `personal.example.json` there and fill in:

- `owner`: your name exactly as banks write it in SMS (used to recognise transfers between your own accounts)
- `keywordRules`: text in the sender or account number → category (e.g. your employer → Salary, your rent paybill account → Rent)
- `ownAccounts`: your own bank account numbers
- `budgets` and `weeklyAllowance`

Everything here can also be set inside the app. The file only fills in what isn't saved yet, so it never overwrites changes made on the phone.

Don't publish APKs you've built yourself: a local build includes your `personal.json`.

## Testing against your own SMS

`ParserRealDataTest` is skipped unless you point it at an SMS export:

```
adb shell "content query --uri content://sms/inbox --projection date:address:body" > sms_export.txt
SMS_FIXTURES=sms_export.txt OWNER_NAME="YOUR NAME" ./gradlew :app:testDebugUnitTest --tests "*ParserRealDataTest*"
```

SMS exports matching `sms_*.txt` are git-ignored. Keep them out of the repo.

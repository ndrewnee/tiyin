# Play Console answers

Answers for the forms under **Policy and programs → App content**, plus the SMS permission declaration. They describe the app as of versionCode 1 (0.1). Update them whenever the app starts sending anything new off the phone.

Checked against Google's help pages: the closed-test rule, the Financial features options, the Data safety definitions of collection and sharing, and the SMS exception. Lines marked *(unchecked)* come from memory and need a look in the Console.

## Before production

A personal developer account created after 13 November 2023 must run a **closed test with at least 12 testers for 14 days in a row** before it can apply for production access. Upload the AAB from `./gradlew bundlePlay` to the closed test track first.

## Privacy policy

https://ndrewnee.github.io/tiyin-privacy/

## App access

**All functionality is available without any access restrictions.** There is no login.

Optional note for reviewers:

> No account is needed. On first launch, tap "Allow reading SMS" and the app builds the spending view from payment SMS sent by the Uzbek banks HUMO, UZCARD, Kapitalbank, Asakabank and Payme. Without such SMS on the device the screens stay empty. You can also tap "Continue without SMS" and load a Payme export (.xlsx) from the period menu. The demo video in the permissions declaration shows the full flow with sample SMS.

## Ads

No, the app does not contain ads.

## Content rating

Category: **Utility, Productivity, Communication, or Other**. *(unchecked)* Answer **No** to every question: there is no violence, sexual content, profanity, drugs, gambling or user-generated content. Users cannot talk to each other, the app does not share the user's location, and it sells nothing. Expected rating: Everyone / 3+.

## Target audience and content

Age groups: **18 and over** only. The app does not appeal to children. Choosing 18+ also keeps it out of the Families policy. *(unchecked)*

## News apps

No.

## Data safety

The only data that leaves the phone is the name of a shop the app could not categorise. It is sent as a search query over HTTPS to orginfo.uz, a public registry of Uzbek organisations. The query has no account, device ID or other identifier. The answers below declare this conservatively. Google's page does not say whether such a query counts. The other reading is the "anonymous data" exemption: answer **No** to collecting or sharing data, and the store page then says "No data collected". That page looks better, but a reviewer may disagree.

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes**
- Do you provide a way for users to request that their data is deleted? **No**. The developer stores nothing. Everything on the phone is deleted with the app or with Clear storage.

Data types: **Financial info → Purchase history** only. Leave every other type unticked, including Messages → SMS or MMS: the SMS text is processed on the device and never sent anywhere.

Purchase history:

- Collected: **Yes**
- Shared: **Yes** (sent to orginfo.uz, a third party)
- Processed ephemerally: **No**. The app keeps nothing, but it cannot vouch for orginfo.uz's server logs.
- Required or optional: **Required**. The lookup has no off switch.
- Purposes, both for collection and for sharing: **App functionality**

Android's own backup is under the user's control and does not count as collection by the developer. *(unchecked)*

## Government apps

No.

## Financial features

**My app doesn't provide any financial features.** Tiyin moves no money, gives no loans or advice and is not a bank. It only shows the user's own spending. None of Google's options (banking, loans, payments, transfers, trading, crypto, credit monitoring, financial advice, insurance) fit. If a reviewer disagrees, the fallback is **Support services → Other**.

## Health apps

No.

## Sensitive permissions: SMS (READ_SMS, RECEIVE_SMS)

**Core functionality:** SMS-based money management (the "Financial management" exception: apps that track or manage budgeting from SMS).

**Describe how the app uses these permissions:**

> Tiyin is a personal spending tracker for Uzbekistan. Uzbek banks (HUMO, UZCARD, Kapitalbank, Asakabank, Payme) send an SMS for every card payment. After the user agrees on the first screen, READ_SMS is used to import payment SMS from these banks that are already in the inbox, and to pick up any missed ones when the app is opened. RECEIVE_SMS adds each new payment as soon as the bank's SMS arrives. The app keeps only messages from these bank senders, skips all other messages and does not keep confirmation codes. It parses the shop, amount and date and shows spending by category, month and year. Messages are stored and processed only on the device and are never uploaded. The only network request is a search on orginfo.uz, a public registry of Uzbek organisations, for the name of a shop the app cannot categorise; it carries no message text and no identifier. The app has no account, ads or analytics. SMS access is optional: users can continue without it and load their history from a Payme export file instead.

**Demo video:** `video/sms-permission-demo.mp4` (49 s). Upload it to YouTube as **Unlisted** and paste the link. It shows:

1. the first screen, which explains which SMS are read and what stays on the phone,
2. the user tapping "Allow reading SMS" and allowing the system dialog,
3. spending built from the bank SMS already on the phone,
4. a new HUMO payment SMS arriving,
5. the new purchase showing up in the list, with its category open for editing.

The video uses made-up SMS on an emulator; no real data appears.

## Store settings

- App or game: **App**
- Category: **Finance**
- Contact email: required, and **shown publicly** on the store page. Use an address you are comfortable publishing. *(unchecked)*
- Website: optional. The privacy policy page can serve.

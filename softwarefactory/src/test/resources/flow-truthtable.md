# Waarheidstabel — gegenereerd uit flow.puml

18 paden, 9 beslissingen. ('-' = niet bereikt op dat pad)

| Payment type? | Check customer >= 18? | Is the customer the LR of the account | What is the type of transaction? | What is the type of ATM transaction? | transaction age | in reservation? | do you recognize the transaction? | age in reservation | → Resultaat |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Card | Yes | Yes | ATM | Geldmaat with Debit card | <120 days | - | - | - | goto report geldmaat issue |
| Card | Yes | Yes | ATM | Geldmaat with Debit card | >=120 days | - | - | - | sorry, we can't help anymore |
| Card | Yes | Yes | ATM | Non geldmaat or credit card | <120 days | - | - | - | goto start dispute |
| Card | Yes | Yes | ATM | Non geldmaat or credit card | >=120 days | - | - | - | sorry, we can't help anymore |
| Card | Yes | Yes | OVPay | - | - | - | - | - | Go to OV, End of flow |
| Card | Yes | Yes | Rest | - | - | Yes | Yes | - | wait until reservation is booked |
| Card | Yes | Yes | Rest | - | - | Yes | No | - | Contact fraud desk |
| Card | Yes | Yes | Rest | - | - | Yes | Unknown | - | Ask the user if this transaction is recognized or not |
| Card | Yes | Yes | Rest | - | - | No | Yes | 0..14 days | sorry, we can't help |
| Card | Yes | Yes | Rest | - | - | No | No | 0..14 days | Contact fraud desk |
| Card | Yes | Yes | Rest | - | - | No | Unknown | 0..14 days | Ask the user if this transaction is recognized or not |
| Card | Yes | Yes | Rest | - | - | No | - | 14..120 days | Go to start dispute, (or more logics needed?) |
| Card | Yes | Yes | Rest | - | - | No | - | >120 days | Go to start dispute, with a warning |
| Card | Yes | No | - | - | - | - | - | - | Sorry we can't help (contact your LR) |
| Card | No | - | - | - | - | - | - | - | Sorry we can't help (contact your LR) |
| SEPA Direct Debit | - | - | - | - | - | - | - | - | Go to direct debit process |
| Wero | - | - | - | - | - | - | - | - | Go to Wero process |
| iDeal | - | - | - | - | - | - | - | - | Go to iDeal process |

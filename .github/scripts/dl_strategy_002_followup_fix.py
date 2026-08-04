from pathlib import Path

path = Path(__file__).with_name("dl_strategy_002_followup.py")
content = path.read_text()
old = '''    "for queue entries, retry attempts, retry budgets, availability, leases, and\\n"
    "immutable workflow deadlines.",
    "for queue entries, retry attempts, retry budgets, availability, leases,\\n"
    "immutable workflow deadlines, and bounded strategy-decision identity.",
'''
new = '''    "> for queue entries, retry attempts, retry budgets, availability, leases, and\\n"
    "> immutable workflow deadlines.",
    "> for queue entries, retry attempts, retry budgets, availability, leases,\\n"
    "> immutable workflow deadlines, and bounded strategy-decision identity.",
'''
if content.count(old) != 1:
    raise SystemExit("Expected one Apple status replacement target in follow-up script.")
path.write_text(content.replace(old, new, 1))
print("Corrected Apple blockquote replacement target.")

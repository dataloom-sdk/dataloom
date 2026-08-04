from pathlib import Path

script_directory = Path(__file__).parent

followup_path = script_directory / "dl_strategy_002_followup.py"
followup_content = followup_path.read_text()
old_doc_target = '''    "for queue entries, retry attempts, retry budgets, availability, leases, and\\n"
    "immutable workflow deadlines.",
    "for queue entries, retry attempts, retry budgets, availability, leases,\\n"
    "immutable workflow deadlines, and bounded strategy-decision identity.",
'''
new_doc_target = '''    "> for queue entries, retry attempts, retry budgets, availability, leases, and\\n"
    "> immutable workflow deadlines.",
    "> for queue entries, retry attempts, retry budgets, availability, leases,\\n"
    "> immutable workflow deadlines, and bounded strategy-decision identity.",
'''
if followup_content.count(old_doc_target) != 1:
    raise SystemExit("Expected one Apple status replacement target in follow-up script.")
followup_path.write_text(followup_content.replace(old_doc_target, new_doc_target, 1))

patch_path = script_directory / "dl_strategy_002_patch.py"
patch_content = patch_path.read_text()
old_regex_call = (
    "updated, count = re.subn(pattern, replacement, content, count=1, flags=re.DOTALL)"
)
new_regex_call = (
    "updated, count = re.subn("
    "pattern, lambda _: replacement, content, count=1, flags=re.DOTALL"
    ")"
)
if patch_content.count(old_regex_call) != 1:
    raise SystemExit("Expected one regex replacement implementation in primary patch script.")
patch_path.write_text(patch_content.replace(old_regex_call, new_regex_call, 1))

print("Corrected documentation target and literal regex replacement semantics.")

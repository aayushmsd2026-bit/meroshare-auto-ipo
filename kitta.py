"""Decide how many units (kitta) to apply for.

How allotment works for general-public IPOs in Nepal (CDSC):
  * If total demand <= units offered, everyone gets everything they applied for.
  * If oversubscribed, units are given by lottery in lots of the minimum unit
    (usually 10). Only if units remain after *every* applicant gets one lot is
    the remainder shared in proportion to the extra units applied for.

So applying for more than the minimum only helps when the issue is
undersubscribed, or when there are so few applicants that everyone gets a lot
and there is a remainder. Almost every Nepali equity IPO is oversubscribed many
times over, so the minimum is nearly always optimal - applying for more just
blocks more money in your bank account for no benefit.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Decision:
    kitta: int
    reason: str


def _round_down(value: int, multiple: int) -> int:
    multiple = max(multiple, 1)
    return (value // multiple) * multiple


def choose_kitta(
    min_unit: int,
    max_unit: int,
    multiple_of: int = 10,
    price_per_unit: float = 100.0,
    subscription_times: float | None = None,
    applicants: int | None = None,
    units_offered: int | None = None,
    account_max_kitta: int | None = None,
    account_max_amount: float | None = None,
) -> Decision:
    """Return the best kitta to apply for, given what we know about the issue.

    subscription_times: general-public demand / units offered (e.g. 12.5 means
        12.5x oversubscribed). None means unknown.
    applicants / units_offered: optional, used for the "everyone gets a lot and
        there is a remainder" case.
    account_max_kitta / account_max_amount: personal caps from the account config.
    """
    min_unit = max(int(min_unit or 10), 1)
    multiple_of = max(int(multiple_of or min_unit), 1)

    # The most this account is willing / allowed to apply for.
    ceiling = int(max_unit or min_unit)
    if account_max_kitta:
        ceiling = min(ceiling, int(account_max_kitta))
    if account_max_amount and price_per_unit:
        ceiling = min(ceiling, int(account_max_amount // price_per_unit))
    ceiling = max(_round_down(ceiling, multiple_of), min_unit)

    if ceiling <= min_unit:
        return Decision(min_unit, "minimum (your limits do not allow more)")

    # Case 1: we know the number of applicants and units offered.
    if applicants and units_offered:
        if applicants * min_unit >= units_offered:
            return Decision(min_unit, f"minimum ({applicants:,} applicants; lottery for {min_unit} kitta each)")
        # Everyone gets a lot and there is a remainder shared pro-rata.
        if subscription_times is not None and subscription_times <= 1:
            return Decision(ceiling, f"maximum allowed ({subscription_times:.2f}x - undersubscribed, full allotment)")
        return Decision(ceiling, "maximum allowed (few applicants; remainder is shared by extra units applied)")

    # Case 2: only the subscription ratio is known.
    if subscription_times is not None:
        if subscription_times < 1:
            return Decision(ceiling, f"maximum allowed ({subscription_times:.2f}x - undersubscribed, full allotment)")
        return Decision(min_unit, f"minimum ({subscription_times:.2f}x oversubscribed; extra kitta does not help)")

    # Case 3: nothing known - general-public IPOs are almost always oversubscribed.
    return Decision(min_unit, "minimum (subscription unknown; IPOs are almost always oversubscribed)")

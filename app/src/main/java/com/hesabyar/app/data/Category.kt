package com.hesabyar.app.data

import androidx.annotation.StringRes
import com.hesabyar.app.R

enum class Category(val key: String, @StringRes val label: Int, val isIncome: Boolean) {
    SALARY("salary", R.string.cat_salary, true),
    SALE("sale", R.string.cat_sale, true),
    GIFT("gift", R.string.cat_gift, true),
    OTHER_INCOME("other_income", R.string.cat_other_income, true),

    FOOD("food", R.string.cat_food, false),
    TRANSPORT("transport", R.string.cat_transport, false),
    BILL("bill", R.string.cat_bill, false),
    SHOPPING("shopping", R.string.cat_shopping, false),
    ENTERTAINMENT("entertainment", R.string.cat_entertainment, false),
    HEALTH("health", R.string.cat_health, false),
    OTHER_EXPENSE("other_expense", R.string.cat_other_expense, false);

    companion object {
        fun fromKey(key: String): Category? = entries.firstOrNull { it.key == key }
    }
}

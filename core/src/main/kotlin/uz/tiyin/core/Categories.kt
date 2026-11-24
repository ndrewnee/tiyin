package uz.tiyin.core

object Categories {
    const val OTHER = "Прочее"
    const val VIA_PAYME = "Оплата через Payme"
    const val TRANSFERS = "Переводы"
    const val INCOME = "Поступления"
    const val OWN = "Между своими счетами"

    private val rules: List<Pair<String, Regex>> = listOf(
        "Такси" to "YANDEXGO TAXI|YANDEX GO|MYTAXI|WB TAXI|TAXI",
        "Продукты и доставка" to "LAVKA|YANDEXEATS|YANDEX EDA|EXPRESS24|KORZINKA|MAKRO|HAVAS|CARREFOUR|BAZAAR|FOOD|QANDOLAT|WHITE DRINK",
        "Животные" to "ZOO|VET",
        "Здоровье" to "FARM|PHARM|APTEKA|DORIXONA|MED CENTER|MEDICAL|CLINIC|KLINIKA|\\bMED\\b",
        "Путешествия" to "AIRWAYS|AIRLINES|AVIA|HOTEL|BOOKING",
        "Маркетплейсы" to "OZON|WB INT|WILDBERRIES|UZUM MARKET|UZUM",
        "Подписки и сервисы" to "ANTHROPIC|OPENAI|GOOGLE|APPLE\\.COM|NETFLIX|SPOTIFY|STEAM|YOUTUBE",
        "Одежда" to "FASHION|ZARA|LC WAIKIKI|DEFACTO|SPORTMASTER",
        "Дом" to "KITCHEN|HOME|MEBEL|TEXTILE",
        "Наличные" to "ATM|BANKOMAT|NALICH",
        VIA_PAYME to "PAYME",
    ).map { (name, re) -> name to Regex(re, RegexOption.IGNORE_CASE) }

    fun categorize(txn: Txn): String = when (txn.kind) {
        Kind.OWN_TRANSFER -> OWN
        Kind.INCOME -> INCOME
        Kind.TRANSFER_OUT -> TRANSFERS
        Kind.PURCHASE -> rules.firstOrNull { (_, re) -> re.containsMatchIn(txn.merchant) }?.first ?: OTHER
    }
}

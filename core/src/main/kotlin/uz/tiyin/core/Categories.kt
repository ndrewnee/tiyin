package uz.tiyin.core

object Categories {
    const val OTHER = "Прочее"
    const val VIA_PAYME = "Оплата через Payme"
    const val TRANSFERS = "Переводы"
    const val INCOME = "Поступления"
    const val OWN = "Между своими счетами"
    const val TAXES = "Налоги и госуслуги"

    private val rules: List<Pair<String, Regex>> = listOf(
        "Такси" to "YANDEXGO TAXI|YANDEX GO|MYTAXI|WB TAXI|TAXI",
        "Продукты и доставка" to "LAVKA|YANDEX ?EATS|YANDEX EDA|EXPRESS24|KORZINKA|MAKRO|HAVAS|CARREFOUR|BAZAAR|FOOD|QANDOLAT|WHITE DRINK",
        "Животные" to "ZOO|VET",
        "Здоровье" to "FARM|PHARM|APTEKA|DORIXONA|MED CENTER|MEDICAL|CLINIC|KLINIKA|\\bMED\\b",
        "Путешествия" to "AIRWAYS|AIRLINES|AVIA|HOTEL|BOOKING",
        "Маркетплейсы" to "OZON|WB INT|WILDBERRIES|UZUM MARKET|UZUM",
        "Подписки и сервисы" to "ANTHROPIC|OPENAI|GOOGLE|APPLE\\.COM|NETFLIX|SPOTIFY|STEAM|YOUTUBE",
        "Одежда" to "FASHION|ZARA|LC WAIKIKI|DEFACTO|SPORTMASTER",
        "Дом" to "KITCHEN|HOME|MEBEL|TEXTILE",
        "Наличные" to "ATM|BANKOMAT|NALICH",
        TAXES to "MUNIS|SOLIQ|GOS PLATEJ|DAVLAT BOJ",
        VIA_PAYME to "PAYME",
    ).map { (name, re) -> name to Regex(re, RegexOption.IGNORE_CASE) }

    /**
     * The dictionary wins, then Payme's own category, then [looked]: categories found for merchants
     * the dictionary does not know (see [Orginfo]).
     */
    fun categorize(txn: Txn, looked: Map<String, String> = emptyMap()): String = when (txn.kind) {
        Kind.OWN_TRANSFER -> OWN
        Kind.INCOME -> INCOME
        Kind.TRANSFER_OUT -> TRANSFERS
        Kind.PURCHASE -> {
            // A name taken from Payme's export is the real merchant, so "Payme Plus" is not a payment via Payme.
            val fromPayme = txn.payme != null && txn.merchant == txn.payme.supplier
            rules.firstOrNull { (name, re) -> !(fromPayme && name == VIA_PAYME) && re.containsMatchIn(txn.merchant) }?.first
                ?: txn.payme?.let { PaymeExport.category(it.category) }
                ?: looked[txn.merchant]
                ?: OTHER
        }
    }
}

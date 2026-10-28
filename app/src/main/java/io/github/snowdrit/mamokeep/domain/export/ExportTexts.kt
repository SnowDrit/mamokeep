package io.github.snowdrit.mamokeep.domain.export

/**
 * Все слова выгрузки.
 *
 * Домен не знает про Android и не может достать строку из ресурсов сам, поэтому тексты
 * приходят снаружи одним объектом. Умолчаний нет намеренно: значение по умолчанию
 * означало бы русский текст, зашитый в код, — ровно то, от чего мы уходим.
 */
data class ExportTexts(
    /** Готовый вопрос в начале — ради него пресеты и существуют. */
    val prompt: (ExportPreset) -> String,
    val questionHeader: String,
    val dataNote: String,

    val carHeader: String,
    val name: String,
    val make: String,
    val model: String,
    val year: String,
    val fuelGrade: String,
    val vin: String,
    val anonymized: String,

    val mileageHeader: String,
    val mileageUnknown: String,
    val mileageNow: String,
    val mileageLastReading: String,
    val mileagePerDay: String,
    val mileageIsEstimate: String,
    val mileageNotEstimated: String,
    val readingsHeader: String,

    val maintenanceHeader: String,
    val maintenanceBasis: String,
    val noItems: String,
    val documentsHeader: String,
    /** Недостаток данных не означает, что работа не выполнялась. */
    val unknownNote: String,

    val overdue: String,
    /**
     * Просрочено только по одной оси. Тогда вторая ось говорит «ещё столько-то», и без
     * названия оси строка спорит сама с собой: «просрочено … осталось 1 г.».
     */
    val overdueByDistance: String,
    val overdueByTime: String,
    val stillDistance: (String) -> String,
    val stillTime: (String) -> String,
    val soon: String,
    val ok: String,
    val notMarked: String,
    val leftDistance: (String) -> String,
    val overDistance: (String) -> String,
    val leftTime: (String) -> String,
    val overTime: (String) -> String,
    val interval: String,
    val or: String,
    val lastTime: String,
    val lastMileage: String,
    val dueDate: String,
    val dueNow: String,
    val dueDistance: String,
    val dueTime: String,
    val distanceUnknown: String,
    val timeUnknown: String,

    val historyHeader: String,
    val andMore: (Int) -> String,
    val noHistory: String,
    val historyDocument: String,
    val note: String,

    val fuelHeader: String,
    val averageConsumption: String,
    val totalDistanceNote: String,
    val fuelMethod: String,
    val fuelNotCalculated: String,
    val noFuelEntries: String,
    val fuelEntriesOmitted: (Int) -> String,
    val costEstimate: String,
    /** «- Стоимость: 5,60 ₽ за километр» */
    val costPer: (String, String) -> String,
    val entriesHeader: String,
    val fullTank: String,
    val chargedToLimit: String,
    val partial: String,
    /** Название вида топлива в родительном падеже: «42 л бензина». */
    val fuelOf: (io.github.snowdrit.mamokeep.domain.FuelType) -> String,
    val fuelTitle: (io.github.snowdrit.mamokeep.domain.FuelType) -> String,
    val gradeName: (io.github.snowdrit.mamokeep.domain.FuelGrade) -> String,
    /** «за» в «42 л бензина за 2 900 ₽». */
    val forWord: String,

    val footer: (String) -> String,
)

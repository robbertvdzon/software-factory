# Core-contractclassificatie

De voormalige core-root is rij voor rij als bestaand cross-modulecontract geïnventariseerd. Ports,
immutable data, enums/sealed resultaten en de bijbehorende pure policies zijn mechanisch naar de
expliciete named interface `core.contracts` verplaatst. Alle productieconsumers importeren uitsluitend
die interface; de core-root is leeg. Trackerexceptions zijn eigendom van `tracker.errors`.

Deze fase verandert geen signatures of gedrag. Verdere opsplitsing naar afzonderlijke domeineigenaren
is een inhoudelijke ownershipwijziging en valt niet onder de mechanische modulemigratie.

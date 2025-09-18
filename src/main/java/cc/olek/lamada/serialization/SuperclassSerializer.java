package cc.olek.lamada.serialization;

/**
 * SuperclassSerializer serializes data based off superclass values and not target class values.
 * When an unknown class is presented to Lamada, if it finds a SuperclassSerializer for one of the unknown's superclasses or interfaces
 * It'll use the first SuperclassSerializer to write that object.
 * <p>
 * A good example is HashMap$Values set. You can't de-serialize it back into HashMap$Values, just into a Set
 * So Map serializers would be SuperclassSerializer's
 */
public interface SuperclassSerializer {
}

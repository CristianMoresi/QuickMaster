package com.quickmaster.processing.dynamics.leveler.model;

/** Fixed-capacity read-only list used by the retained M-004 graph. */
public final class FrozenList<E>
{
    private final Object[] elements;

    public FrozenList(Object[] source)
    {
        if (source == null)
        {
            throw new IllegalArgumentException("FrozenList source must not be null.");
        }
        this.elements = new Object[source.length];
        System.arraycopy(source, 0, this.elements, 0, source.length);
        for (int i = 0; i < this.elements.length; i++)
        {
            if (this.elements[i] == null)
            {
                throw new IllegalArgumentException("FrozenList elements must not be null.");
            }
        }
    }

    public int size()
    {
        return elements.length;
    }

    @SuppressWarnings("unchecked")
    public E get(int index)
    {
        if (index < 0 || index >= elements.length)
        {
            throw new IndexOutOfBoundsException("FrozenList index " + index);
        }
        return (E) elements[index];
    }

    public Object[] toArray()
    {
        Object[] copy = new Object[elements.length];
        System.arraycopy(elements, 0, copy, 0, elements.length);
        return copy;
    }
}

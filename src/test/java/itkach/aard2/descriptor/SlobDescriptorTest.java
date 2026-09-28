package itkach.aard2.descriptor;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import itkach.aard2.slob.SlobTags;

/**
 * Unit tests for {@link SlobDescriptor} display-name resolution.
 *
 * <p>Only the label helpers are covered: they are the sole part of the
 * descriptor with no Android dependency.</p>
 */
public class SlobDescriptorTest {

    private SlobDescriptor descriptor;

    @Before
    public void setUp() {
        descriptor = new SlobDescriptor();
        descriptor.tags.put(SlobTags.TAG_LABEL, "Wikipedia (en)");
    }

    @Test
    public void labelFallsBackToDictionaryLabel() {
        assertEquals("Wikipedia (en)", descriptor.getLabel());
    }

    @Test
    public void customNameOverridesDictionaryLabel() {
        descriptor.displayName = "Simple Wikipedia";
        assertEquals("Simple Wikipedia", descriptor.getLabel());
    }

    @Test
    public void blankCustomNameFallsBackToDictionaryLabel() {
        descriptor.displayName = "   ";
        assertEquals("Wikipedia (en)", descriptor.getLabel());
        descriptor.displayName = "";
        assertEquals("Wikipedia (en)", descriptor.getLabel());
    }

    @Test
    public void missingLabelTagYieldsPlaceholder() {
        descriptor.tags.clear();
        assertEquals("???", descriptor.getLabel());
        assertEquals("???", descriptor.getOriginalLabel());
    }

    @Test
    public void originalLabelIgnoresCustomName() {
        descriptor.displayName = "Simple Wikipedia";
        assertEquals("Wikipedia (en)", descriptor.getOriginalLabel());
    }
}

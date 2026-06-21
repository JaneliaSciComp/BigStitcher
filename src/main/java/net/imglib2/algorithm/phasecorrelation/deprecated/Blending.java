/*-
 * #%L
 * Multiview stitching of large datasets.
 * %%
 * Copyright (C) 2016 - 2026 Big Stitcher developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.imglib2.algorithm.phasecorrelation.deprecated;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.type.numeric.real.FloatType;

/** 
 * 
 * RealRandomAccess that computed cosine-blending for a certain interval
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 * @deprecated
 */
public class Blending implements RealRandomAccessible< FloatType >
{
	final Interval interval;
	final float[] border, blending;

	/**
	 * RealRandomAccess that computes a blending function for a certain {@link Interval}
	 * 
	 * @param interval - the interval it is defined on (return zero outside of it)
	 * @param border - how many pixels to skip before starting blending (on each side of each dimension)
	 * @param blending - how many pixels to compute the blending function on (on each side of each dimension)
	 */
	public Blending( final Interval interval, final float[] border, final float[] blending )
	{
		// in case the interval is actually image data re-instantiate just a simple FinalInterval
		this.interval = new FinalInterval( interval );
		this.border = border;
		this.blending = blending;
	}

	@Override
	public int numDimensions() { return interval.numDimensions(); }

	@Override
	public RealRandomAccess<FloatType> realRandomAccess()
	{
		return new BlendingRealRandomAccess( interval, border, blending );
	}

	@Override
	public RealRandomAccess<FloatType> realRandomAccess( final RealInterval interval )
	{
		return realRandomAccess();
	}

	@Override
	public FloatType getType()
	{
		return new FloatType();
	}
}

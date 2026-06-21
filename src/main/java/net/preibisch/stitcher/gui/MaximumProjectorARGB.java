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
package net.preibisch.stitcher.gui;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

import bdv.viewer.Source;
import bdv.viewer.render.AccumulateProjector;
import bdv.viewer.render.AccumulateProjectorFactory;
import bdv.viewer.render.VolatileProjector;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.ARGBType;

public class MaximumProjectorARGB extends AccumulateProjector< ARGBType, ARGBType >
{
	public static AccumulateProjectorFactory< ARGBType > factory = new AccumulateProjectorFactory< ARGBType >()
	{
		@Override
		public VolatileProjector createAccumulateProjector(
				ArrayList< VolatileProjector > sourceProjectors,
				ArrayList< Source< ? > > sources,
				ArrayList< ? extends RandomAccessible< ? extends ARGBType > > sourceScreenImages,
				RandomAccessibleInterval< ARGBType > targetScreenImage,
				int numThreads,
				ExecutorService executorService)
		{
			return new MaximumProjectorARGB( sourceProjectors, sourceScreenImages, targetScreenImage, numThreads, executorService );
		}
	};

	public MaximumProjectorARGB(
			final ArrayList< VolatileProjector > sourceProjectors,
			final ArrayList< ? extends RandomAccessible< ? extends ARGBType > > sources,
			final RandomAccessibleInterval< ARGBType > target,
			final int numThreads,
			final ExecutorService executorService )
	{
		super( sourceProjectors, sources, target, numThreads, executorService );
	}

	@Override
	protected void accumulate( final Cursor< ? extends ARGBType >[] accesses, final ARGBType target )
	{
		int aMax = 0, rMax = 0, gMax = 0, bMax = 0;
		for ( final Cursor< ? extends ARGBType > access : accesses )
		{
			final int value = access.get().get();
			final int a = ARGBType.alpha( value );
			final int r = ARGBType.red( value );
			final int g = ARGBType.green( value );
			final int b = ARGBType.blue( value );
			
			aMax = Math.max( aMax, a );
			rMax = Math.max( rMax, r );
			gMax = Math.max( gMax, g );
			bMax = Math.max( bMax, b );
		}
		
		if ( aMax > 255 )
			aMax = 255;
		if ( rMax > 255 )
			rMax = 255;
		if ( gMax > 255 )
			gMax = 255;
		if ( bMax > 255 )
			bMax = 255;
		
		
		target.set( ARGBType.rgba( rMax, gMax, bMax, aMax ) );
	}
}

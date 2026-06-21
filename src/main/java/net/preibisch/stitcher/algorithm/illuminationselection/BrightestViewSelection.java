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
package net.preibisch.stitcher.algorithm.illuminationselection;

import java.util.Collection;
import java.util.Date;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.deconvolution.normalization.AdjustInput;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class BrightestViewSelection extends BasicViewSelection<ViewId>
{
	public BrightestViewSelection(AbstractSequenceDescription<?,?,?> sd) 
	{
		super( sd );
	}
	
	public BrightestViewSelection(AbstractSpimData<AbstractSequenceDescription<?,?,?>> data) { this(data.getSequenceDescription()); }

	public <T extends RealType< T >> ViewId getBestViewMean(Collection<? extends ViewId> views)
	{
		if (views.size() < 1)
			return null;
		
		BasicImgLoader imgLoader = sd.getImgLoader();
		
		ViewId currentBest = null;
		double currentBestMean = -Double.MAX_VALUE;
		
		if (MultiResolutionImgLoader.class.isInstance( imgLoader ))
		{
			MultiResolutionImgLoader mrImgLoader = (MultiResolutionImgLoader) imgLoader;
			
			for (ViewId view : views)
			{
				MultiResolutionSetupImgLoader< ? > setupImgLoader = mrImgLoader.getSetupImgLoader( view.getViewSetupId() );

				RandomAccessibleInterval< T > image = (RandomAccessibleInterval< T >) setupImgLoader.getImage( view.getTimePointId(), setupImgLoader.getMipmapResolutions().length - 1 );

				IterableInterval< T > iterableImg = Views.iterable( image );
				double mean = AdjustInput.sumImg( iterableImg ) / (double)iterableImg.size();

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Evaluated view " + Group.pvid( view ) + 
						" at resolution " + Util.printCoordinates( setupImgLoader.getMipmapResolutions()[ setupImgLoader.getMipmapResolutions().length - 1 ] ) + ": " + mean );

				if (currentBest == null)
				{
					currentBest = view;
					currentBestMean = mean;
				}
				else if (mean >= currentBestMean )
				{
					currentBest = view;
					currentBestMean = mean;
				}
			}
		}
		else
		{
			for (ViewId view : views)
			{
				RandomAccessibleInterval< T > image = (RandomAccessibleInterval< T >) imgLoader.getSetupImgLoader( view.getViewSetupId() ).getImage( view.getTimePointId() );

				IterableInterval< T > iterableImg = Views.iterable( image );
				double mean = AdjustInput.sumImg( iterableImg ) / (double)iterableImg.size();

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Evaluated view " + Group.pvid( view ) + " at full resolution: " + mean );

				if (currentBest == null)
				{
					currentBest = view;
					currentBestMean = mean;
				}
				else if (mean >= currentBestMean )
				{
					currentBest = view;
					currentBestMean = mean;
				}
			}
			
		}
				
		return currentBest;
	}
	
	
	
	public <T extends RealType<T>> T getMean(IterableInterval< T > img)
	{
		RealSum sum = new RealSum();
		long nPix = 0;
		
		for (T t : img)
		{
			sum.add( t.getRealDouble() );
			nPix++;
		}
		
		T res = img.firstElement().createVariable();
		res.setReal( sum.getSum()/nPix );
		return res;
		
	}
	
	@Override
	public ViewId getBestView(Collection< ? extends ViewId > views)
	{
		return getBestViewMean( views );
	}

	@Override
	public boolean runMultithreaded()
	{
		return true;
	}
}

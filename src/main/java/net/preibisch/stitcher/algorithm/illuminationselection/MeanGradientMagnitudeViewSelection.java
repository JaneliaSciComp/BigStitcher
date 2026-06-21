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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.VectorUtil;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class MeanGradientMagnitudeViewSelection extends BasicViewSelection<ViewId> implements ViewSelection<ViewId> {

	public MeanGradientMagnitudeViewSelection(AbstractSequenceDescription<?, ?, ?> sd) {
		super(sd);
	}

	public MeanGradientMagnitudeViewSelection(AbstractSpimData<AbstractSequenceDescription<?,?,?>> data)
	{
		this(data.getSequenceDescription());
	}

	public ViewId getBestView(Collection<? extends ViewId> views)
	{
		return getBestViewMeanGradientMagnitude(views);
	};

	public <T extends RealType< T >> ViewId getBestViewMeanGradientMagnitude(Collection<? extends ViewId> views)
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

				double mean = getMeanGradientMagnitude(image);

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

				double mean = getMeanGradientMagnitude(image);

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

	public static <T extends RealType<T>> double getMeanGradientMagnitude(RandomAccessibleInterval<T> image) {

		 ExtendedRandomAccessibleInterval<T, RandomAccessibleInterval<T>> oobImage = Views.extendMirrorSingle( image );

		final Vector<ImagePortion> portions = FusionTools.divideIntoPortions(Views.iterable(image).size());
		final AtomicInteger ai = new AtomicInteger(0);
		final ExecutorService service = Executors.newFixedThreadPool(Threads.numThreads());

		final ArrayList<Callable<Double>> calls = new ArrayList<Callable<Double>>();

		for (int i = 0; i<portions.size(); i++)
		{
			final Callable<Double> call = new Callable<Double>() {
	
				@Override
				public Double call() throws Exception {

					final ImagePortion portion = portions.elementAt(ai.getAndIncrement());

					final double[] grad = new double[image.numDimensions()];
					final RealSum sum = new RealSum();
					final RandomAccess<T> ra = oobImage.randomAccess();
					final Cursor<T> c = Views.iterable(image).cursor();

					c.jumpFwd(portion.getStartPosition());

					for (int j=0; j<portion.getLoopSize(); j++)
					{
						c.fwd();
						ra.setPosition(c);
						for (int d=0; d<image.numDimensions(); d++)
						{
							ra.bck(d);
							final double f0 = ra.get().getRealDouble();
							ra.move(2, d);
							final double f1 = ra.get().getRealDouble();
							ra.bck(d);
							grad[d] = (f1 - f0) / 2.0;
						}
						sum.add(VectorUtil.getVectorLength(grad));
					}
					return sum.getSum();
				}
			};
			calls.add(call);
		}

		List<Future<Double>> futures = null;
		try {
			 futures = service.invokeAll(calls);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		double res = 0;
		for (final Future<Double> f : futures)
			try {
				res += f.get();
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		
		service.shutdown();
		return res / Views.iterable(image).size();		
	}

	@Override
	public boolean runMultithreaded()
	{
		return true;
	}

}

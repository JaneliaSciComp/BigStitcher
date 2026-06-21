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
package net.imglib2.algorithm.phasecorrelation;

import java.io.File;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.Sampler;
import net.imglib2.algorithm.phasecorrelation.deprecated.Blending;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.fusion.transformed.weights.BlendingRealRandomAccessible;


public class BlendedExtendedMirroredRandomAccesible2 <T extends RealType<T>>implements RandomAccessible<T> {

	private RandomAccessibleInterval<T> img;
	BlendingRealRandomAccessible blending;
	private int numDimensions;
	private FinalInterval extDims;
	
	public BlendedExtendedMirroredRandomAccesible2(RandomAccessibleInterval<T> img, int[] border) {
		this.img = img;
		this.numDimensions = img.numDimensions();
		
		float[] blendingBorder = new float[numDimensions];
		float[] border2 = new float[numDimensions];
		
		this.extDims = new FinalInterval(img);		
		for (int i = 0; i < numDimensions; i++)
		{
			extDims = Intervals.expand(extDims, border[i], i);
			blendingBorder[i] = border[i];
			border2[i] = 0.0f;
		}
		
		this.blending = new BlendingRealRandomAccessible(extDims, border2, blendingBorder);
	}
	
	
	@Override
	public int numDimensions() {
		return numDimensions;
	}
	
	public FinalInterval getExtInterval() {
		return extDims;
	}

	@Override
	public T getType()
	{
		return img.getType();
	}

	/**
	 * TODO: For efficiency reasons we should implement it as a RandomAccess that actually updates the underlying
	 * imgRA for every move. This way, the outofbounds can work very efficiently when it is iterated through Views.iterable().cursor()
	 */
	public class BlendedRandomAccess extends Point implements RandomAccess<T>
	{
		public BlendedRandomAccess() {
			super(img.numDimensions());
		}

		RandomAccess<T> imgRA = Views.extendMirrorSingle(img).randomAccess();
		RealRandomAccess<FloatType> blendRA = blending.realRandomAccess();
		T val = imgRA.get().createVariable();

		@Override
		public T get() {
			val.setReal(imgRA.get().getRealFloat() * blendRA.get().getRealFloat());
			return val;
		}

		@Override
		public void fwd(int d) {
			super.fwd(d);
			imgRA.fwd(d);
			blendRA.fwd(d);
		}

		@Override
		public void bck(int d) {
			super.bck(d);
			imgRA.bck(d);
			blendRA.bck(d);
		}

		@Override
		public void move(int distance, int d) {
			super.move(distance, d);
			imgRA.move(distance, d);
			blendRA.move(distance, d);
		}

		@Override
		public void move(long distance, int d) {
			super.move(distance, d);
			imgRA.move(distance, d);
			blendRA.move(distance, d);
		}

		@Override
		public void move(Localizable localizable) {
			super.move(localizable);
			imgRA.move(localizable);
			blendRA.move(localizable);
		}

		@Override
		public void move(int[] distance) {
			super.move(distance);
			imgRA.move(distance);
			blendRA.move(distance);
		}

		@Override
		public void move(long[] distance) {
			super.move(distance);
			imgRA.move(distance);
			blendRA.move(distance);
		}

		@Override
		public void setPosition(Localizable localizable) {
			super.setPosition(localizable);
			imgRA.setPosition(localizable);
			blendRA.setPosition(localizable);
		}

		@Override
		public void setPosition(int[] position) {
			super.setPosition(position);
			imgRA.setPosition(position);
			blendRA.setPosition(position);
		}

		@Override
		public void setPosition(long[] position) {
			super.setPosition(position);
			imgRA.setPosition(position);
			blendRA.setPosition(position);
		}

		@Override
		public void setPosition(int position, int d) {
			super.setPosition(position, d);
			imgRA.setPosition(position, d);
			blendRA.setPosition(position, d);
		}

		@Override
		public void setPosition(long position, int d) {
			super.setPosition(position, d);
			imgRA.setPosition(position, d);
			blendRA.setPosition(position, d);
		}

		@Override
		public RandomAccess<T> copy() {
			BlendedRandomAccess a = new BlendedRandomAccess();
			a.move(this);
			return a;
		}
		
	}
	
	@Override
	public RandomAccess<T> randomAccess() {
		return new BlendedRandomAccess();
	}

	@Override
	public RandomAccess<T> randomAccess(Interval interval) {
		return randomAccess();
	}
	

	public static void main(String[] args) {
		
		new ImageJ();
		Img<FloatType> img1 = ImgLib2Util.openAs32Bit(new File("src/main/resources/img1.tif"));
		long[] dims = new long[img1.numDimensions()];

		BlendedExtendedMirroredRandomAccesible2<FloatType> ext = new BlendedExtendedMirroredRandomAccesible2<FloatType>(img1, new int[]{100, 100, 10});
		
		ext.getExtInterval().dimensions(dims);		
		Img<FloatType> img2 = ArrayImgs.floats(dims);

		// TODO: For efficiency reasons we should now also iterate the BlendedExtendedMirroredRandomAccesible and not the image
		long start = System.currentTimeMillis();
		
		Cursor<FloatType> c = img2.cursor();
		for (FloatType e : Views.iterable(Views.interval(ext, ext.getExtInterval())))
		{
			c.fwd();
			c.get().set(e);
		}
		
		long end = System.currentTimeMillis();		
		System.out.println(end-start);
		
		ImageJFunctions.show(img2);	
		
		//RandomAccessibleInterval<FloatType> img3 = Views.interval(ext, ext.getExtInterval());		
		//ImageJFunctions.show(img3);
		

		

	}
}

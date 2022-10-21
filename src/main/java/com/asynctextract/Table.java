package com.asynctextract;

import java.util.List;
import java.util.Map;

import com.amazonaws.services.textract.model.Block;

public class Table {
	public Block tableBlock;
	public List<Block> cellBlocks;
	public List<Block> cellWords;
	public List<Map<Integer, String>> columnNamesList;
	public Map<String, Integer> coulumnRowscount;
	public List<Map<String, Object>> tableData;

	public Table() {
		super();
	}

	public Block getTableBlock() {
		return tableBlock;
	}

	public void setTableBlock(Block tableBlock) {
		this.tableBlock = tableBlock;
	}

	public List<Block> getCellBlocks() {
		return cellBlocks;
	}

	public void setCellBlocks(List<Block> cellBlocks) {
		this.cellBlocks = cellBlocks;
	}

	public List<Block> getCellWords() {
		return cellWords;
	}

	public void setCellWords(List<Block> cellWords) {
		this.cellWords = cellWords;
	}

	public Map<String, Integer> getCoulumnRowscount() {
		return coulumnRowscount;
	}

	public void setCoulumnRowscount(Map<String, Integer> coulumnRowscount) {
		this.coulumnRowscount = coulumnRowscount;
	}

	public List<Map<Integer, String>> getColumnNamesList() {
		return columnNamesList;
	}

	public void setColumnNamesList(List<Map<Integer, String>> columnNamesList) {
		this.columnNamesList = columnNamesList;
	}

	public List<Map<String, Object>> getTableData() {
		return tableData;
	}

	public void setTableData(List<Map<String, Object>> tableData) {
		this.tableData = tableData;
	}

	@Override
	public String toString() {
		return "Table [tableBlock=" + tableBlock + ", cellBlocks=" + cellBlocks + ", cellWords=" + cellWords
				+ ", columnNamesList=" + columnNamesList + ", coulumnRowscount=" + coulumnRowscount + ", tableData="
				+ tableData + "]";
	}

}
